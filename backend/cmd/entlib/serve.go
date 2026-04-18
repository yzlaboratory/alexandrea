package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/yzlaboratory/entertainment-library/backend/internal/auth"
	"github.com/yzlaboratory/entertainment-library/backend/internal/httpx"
	"github.com/yzlaboratory/entertainment-library/backend/internal/storage"
	"github.com/yzlaboratory/entertainment-library/backend/internal/tmdb"
)

func runServe() {
	logger := slog.New(slog.NewTextHandler(os.Stdout, nil))

	dsn := envOr("ENTLIB_DB_DSN", "./entlib.sqlite")
	db, err := storage.Open(dsn)
	if err != nil {
		logger.Error("open db", "err", err)
		os.Exit(1)
	}
	defer db.Close()

	if err := storage.Migrate(db); err != nil {
		logger.Error("migrate", "err", err)
		os.Exit(1)
	}
	logger.Info("db ready", "dsn", dsn)

	secure := true
	if v := os.Getenv("ENTLIB_COOKIE_SECURE"); v != "" {
		parsed, err := strconv.ParseBool(v)
		if err != nil {
			logger.Error("ENTLIB_COOKIE_SECURE parse", "err", err)
			os.Exit(1)
		}
		secure = parsed
	}

	tmdbClient, err := buildTMDBClient(logger)
	if err != nil {
		logger.Error("tmdb client", "err", err)
		os.Exit(1)
	}

	deps := httpx.Deps{
		DB:           db,
		Sessions:     auth.NewSessionStore(db),
		CookieSecure: secure,
		TMDB:         tmdbClient,
	}

	addr := envOr("ENTLIB_HTTP_ADDR", ":8080")
	srv := &http.Server{
		Addr:              addr,
		Handler:           httpx.NewRouter(deps),
		ReadHeaderTimeout: 5 * time.Second,
	}

	go func() {
		logger.Info("listening", "addr", addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("listen failed", "err", err)
			os.Exit(1)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, os.Interrupt, syscall.SIGTERM)
	<-quit

	logger.Info("shutting down")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		logger.Error("shutdown", "err", err)
		os.Exit(1)
	}
}

// buildTMDBClient reads TMDB_API_KEY from the environment and returns a
// configured client. Returning (nil, nil) is the "tmdb disabled" signal that
// the HTTP handlers turn into 503 — only allowed when ENTLIB_TMDB_OPTIONAL
// is truthy, which is the local-dev-without-a-key case. In production the
// systemd EnvironmentFile carries the key (ADR 0004) so startup fails
// loudly if it's missing.
func buildTMDBClient(logger *slog.Logger) (httpx.TMDBSearcher, error) {
	key := os.Getenv("TMDB_API_KEY")
	if key != "" {
		logger.Info("tmdb ready")
		return tmdb.New(key, tmdb.Options{}), nil
	}
	optional := false
	if v := os.Getenv("ENTLIB_TMDB_OPTIONAL"); v != "" {
		parsed, err := strconv.ParseBool(v)
		if err != nil {
			return nil, err
		}
		optional = parsed
	}
	if !optional {
		return nil, errors.New("TMDB_API_KEY is required (set ENTLIB_TMDB_OPTIONAL=true for dev)")
	}
	logger.Warn("tmdb disabled: TMDB_API_KEY empty and ENTLIB_TMDB_OPTIONAL=true")
	return nil, nil
}
