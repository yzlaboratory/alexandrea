package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/yzlaboratory/entertainment-library/backend/internal/httpx"
	"github.com/yzlaboratory/entertainment-library/backend/internal/storage"
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

	addr := envOr("ENTLIB_HTTP_ADDR", ":8080")
	srv := &http.Server{
		Addr:              addr,
		Handler:           httpx.NewRouter(),
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
