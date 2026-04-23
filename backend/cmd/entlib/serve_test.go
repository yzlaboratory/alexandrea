package main

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/yzlaboratory/entertainment-library/backend/internal/tmdb"
)

func silentLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func TestBuildTMDBClient_WithKey(t *testing.T) {
	t.Setenv("TMDB_API_KEY", "deadbeef")
	t.Setenv("ENTLIB_TMDB_OPTIONAL", "")
	c, err := buildTMDBClient(silentLogger())
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	if c == nil {
		t.Fatal("client = nil, want non-nil when key is set")
	}
}

func TestBuildTMDBClient_MissingKeyRejected(t *testing.T) {
	t.Setenv("TMDB_API_KEY", "")
	t.Setenv("ENTLIB_TMDB_OPTIONAL", "")
	_, err := buildTMDBClient(silentLogger())
	if err == nil {
		t.Fatal("missing key must fail unless ENTLIB_TMDB_OPTIONAL=true")
	}
	if !strings.Contains(err.Error(), "TMDB_API_KEY") {
		t.Errorf("err = %v; want to mention TMDB_API_KEY", err)
	}
}

func TestBuildTMDBClient_OptionalAllowsMissing(t *testing.T) {
	t.Setenv("TMDB_API_KEY", "")
	t.Setenv("ENTLIB_TMDB_OPTIONAL", "true")
	c, err := buildTMDBClient(silentLogger())
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	if c != nil {
		t.Fatal("client must be nil when key missing + optional=true")
	}
}

func TestBuildTMDBClient_InvalidOptionalRejected(t *testing.T) {
	t.Setenv("TMDB_API_KEY", "")
	t.Setenv("ENTLIB_TMDB_OPTIONAL", "not-a-bool")
	if _, err := buildTMDBClient(silentLogger()); err == nil {
		t.Fatal("invalid ENTLIB_TMDB_OPTIONAL must surface a parse error")
	}
}

func TestBuildTMDBClient_BaseURLOverride(t *testing.T) {
	var hits atomic.Int32
	stub := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"results":[]}`))
	}))
	defer stub.Close()

	t.Setenv("TMDB_API_KEY", "deadbeef")
	t.Setenv("ENTLIB_TMDB_OPTIONAL", "")
	t.Setenv("ENTLIB_TMDB_BASE_URL", stub.URL)

	c, err := buildTMDBClient(silentLogger())
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	client, ok := c.(*tmdb.Client)
	if !ok {
		t.Fatalf("client type = %T; want *tmdb.Client", c)
	}
	if _, err := client.Search(context.Background(), "anything"); err != nil {
		t.Fatalf("search: %v", err)
	}
	if got := hits.Load(); got != 1 {
		t.Fatalf("stub hit count = %d; want 1 (request must route through ENTLIB_TMDB_BASE_URL, not real TMDB)", got)
	}
}
