//go:build integration

// Optional hits-real-TMDB test. Run with:
//
//   TMDB_API_KEY=... go test -tags=integration ./internal/tmdb/
//
// These tests make real network calls and cost a tiny amount of quota.
// They verify that TMDB hasn't changed its response shape out from under us.
package tmdb

import (
	"context"
	"os"
	"testing"
	"time"
)

func realClient(t *testing.T) *Client {
	t.Helper()
	key := os.Getenv("TMDB_API_KEY")
	if key == "" {
		t.Skip("TMDB_API_KEY not set; skipping integration test")
	}
	return New(key, Options{})
}

func TestIntegration_SearchInception(t *testing.T) {
	c := realClient(t)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	results, err := c.Search(ctx, "Inception")
	if err != nil {
		t.Fatalf("search: %v", err)
	}
	if len(results) == 0 {
		t.Fatal("expected at least one result for 'Inception'")
	}
	// The canonical 2010 Nolan movie is TMDB id 27205. We don't require
	// it be first (popularity can shift), only present.
	for _, r := range results {
		if r.TMDBID == 27205 && r.Kind == KindMovie {
			return
		}
	}
	t.Errorf("expected tmdb_id=27205 in results, got %+v", results)
}

func TestIntegration_GetInception(t *testing.T) {
	c := realClient(t)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	r, err := c.Get(ctx, KindMovie, 27205)
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if r.Title == "" || r.Year != 2010 {
		t.Errorf("unexpected shape: %+v", r)
	}
}
