package main

import (
	"io"
	"log/slog"
	"strings"
	"testing"
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
