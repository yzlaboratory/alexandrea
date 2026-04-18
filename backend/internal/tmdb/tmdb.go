// Package tmdb is a tiny server-side client for api.themoviedb.org per ADR
// 0004. Scope:
//
//   - Search(ctx, query) proxies /search/multi, filtering out people.
//   - Get(ctx, kind, id) proxies /movie/:id or /tv/:id.
//   - 3-retry exponential backoff (200/400/800 ms) on 429 + 5xx.
//   - 10-second overall request timeout per call.
//   - singleflight dedupe so a debounced search box does not stampede.
//
// Explicitly out of scope: caching, rate-limit token bucket, pagination,
// multi-language. All per ADR 0004's rejected-alternatives list.
package tmdb

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"golang.org/x/sync/singleflight"
)

// Kind is the TMDB media type we care about, normalised to the strings we
// use throughout the app (see specs/01-titles.md).
type Kind string

const (
	KindMovie  Kind = "movie"
	KindSeries Kind = "series"
)

// Result is the minimal shape we keep per title. Matches specs/01-titles.md:
// poster_path is a TMDB-relative path (e.g. "/abc.jpg"); the frontend
// prepends image.tmdb.org/t/p/{size}.
type Result struct {
	TMDBID     int    `json:"tmdb_id"`
	Kind       Kind   `json:"kind"`
	Title      string `json:"title"`
	Year       int    `json:"year,omitempty"`
	PosterPath string `json:"poster_path,omitempty"`
	Overview   string `json:"overview,omitempty"`
}

// Client is a TMDB v3 API client. The zero value is not usable; build one
// with New.
type Client struct {
	apiKey     string
	baseURL    string
	httpClient *http.Client
	retry      retryPolicy
	flight     singleflight.Group
}

type retryPolicy struct {
	base    time.Duration
	factor  int
	attempts int
}

// Options lets tests (and the eventual serve wiring) override the defaults.
type Options struct {
	// BaseURL overrides https://api.themoviedb.org/3. Used by tests to
	// point at an httptest.Server.
	BaseURL string
	// HTTPClient overrides the per-request 10s-timeout http.Client. Mostly
	// for tests.
	HTTPClient *http.Client
	// RetryBase overrides the 200 ms first-retry delay. Tests set this to
	// a microsecond so the retry path doesn't make them slow.
	RetryBase time.Duration
}

// New returns a Client. apiKey is required except in contexts where the
// caller explicitly permits a missing key (see serve's ENTLIB_TMDB_OPTIONAL).
func New(apiKey string, opts Options) *Client {
	base := opts.BaseURL
	if base == "" {
		base = "https://api.themoviedb.org/3"
	}
	httpClient := opts.HTTPClient
	if httpClient == nil {
		httpClient = &http.Client{Timeout: 10 * time.Second}
	}
	retryBase := opts.RetryBase
	if retryBase == 0 {
		retryBase = 200 * time.Millisecond
	}
	return &Client{
		apiKey:     apiKey,
		baseURL:    strings.TrimRight(base, "/"),
		httpClient: httpClient,
		retry:      retryPolicy{base: retryBase, factor: 2, attempts: 3},
	}
}

// ErrUpstream is returned when TMDB itself refused or failed the call. The
// caller can inspect the wrapped status code.
type ErrUpstream struct {
	Status int
	Body   string
}

func (e *ErrUpstream) Error() string {
	return fmt.Sprintf("tmdb: upstream status %d: %s", e.Status, e.Body)
}

// ErrNotFound is a specific sentinel for 404s, so handlers can map it to
// HTTP 404 rather than the generic 502 bucket.
var ErrNotFound = errors.New("tmdb: not found")

// Search calls /search/multi and returns only movie + tv results, shaped
// into our Result DTO. The zero/empty-query case is a programmer error —
// callers should reject empty queries before reaching here.
func (c *Client) Search(ctx context.Context, query string) ([]Result, error) {
	if query == "" {
		return nil, errors.New("tmdb: empty query")
	}
	key := "search:" + query

	raw, err, _ := c.flight.Do(key, func() (any, error) {
		q := url.Values{}
		q.Set("query", query)
		q.Set("language", "en-US")
		q.Set("region", "US")
		q.Set("include_adult", "false")
		return c.fetchJSON(ctx, "/search/multi", q)
	})
	if err != nil {
		return nil, err
	}

	var resp struct {
		Results []struct {
			ID            int     `json:"id"`
			MediaType     string  `json:"media_type"`
			Title         string  `json:"title"`
			Name          string  `json:"name"`
			Overview      string  `json:"overview"`
			PosterPath    string  `json:"poster_path"`
			ReleaseDate   string  `json:"release_date"`
			FirstAirDate  string  `json:"first_air_date"`
			Popularity    float64 `json:"popularity"`
		} `json:"results"`
	}
	if err := json.Unmarshal(raw.([]byte), &resp); err != nil {
		return nil, fmt.Errorf("tmdb: decode search: %w", err)
	}

	out := make([]Result, 0, len(resp.Results))
	for _, r := range resp.Results {
		kind, ok := fromMediaType(r.MediaType)
		if !ok {
			continue
		}
		title := r.Title
		dateStr := r.ReleaseDate
		if kind == KindSeries {
			title = r.Name
			dateStr = r.FirstAirDate
		}
		out = append(out, Result{
			TMDBID:     r.ID,
			Kind:       kind,
			Title:      title,
			Year:       yearOf(dateStr),
			PosterPath: r.PosterPath,
			Overview:   r.Overview,
		})
	}
	return out, nil
}

// Get fetches a single title by kind + id. kind must be "movie" or "series".
func (c *Client) Get(ctx context.Context, kind Kind, id int) (Result, error) {
	if id <= 0 {
		return Result{}, errors.New("tmdb: invalid id")
	}
	path, err := pathFor(kind, id)
	if err != nil {
		return Result{}, err
	}
	key := "get:" + string(kind) + ":" + strconv.Itoa(id)

	raw, err, _ := c.flight.Do(key, func() (any, error) {
		q := url.Values{}
		q.Set("language", "en-US")
		q.Set("region", "US")
		return c.fetchJSON(ctx, path, q)
	})
	if err != nil {
		return Result{}, err
	}

	var resp struct {
		ID           int    `json:"id"`
		Title        string `json:"title"`
		Name         string `json:"name"`
		Overview     string `json:"overview"`
		PosterPath   string `json:"poster_path"`
		ReleaseDate  string `json:"release_date"`
		FirstAirDate string `json:"first_air_date"`
	}
	if err := json.Unmarshal(raw.([]byte), &resp); err != nil {
		return Result{}, fmt.Errorf("tmdb: decode get: %w", err)
	}

	title := resp.Title
	dateStr := resp.ReleaseDate
	if kind == KindSeries {
		title = resp.Name
		dateStr = resp.FirstAirDate
	}
	return Result{
		TMDBID:     resp.ID,
		Kind:       kind,
		Title:      title,
		Year:       yearOf(dateStr),
		PosterPath: resp.PosterPath,
		Overview:   resp.Overview,
	}, nil
}

// fetchJSON performs GET baseURL+path?query with the retry policy attached.
// Returns the raw response body on 2xx. Maps 404 to ErrNotFound; everything
// non-2xx that retries exhaust is wrapped in *ErrUpstream.
func (c *Client) fetchJSON(ctx context.Context, path string, query url.Values) ([]byte, error) {
	query.Set("api_key", c.apiKey)
	target := c.baseURL + path + "?" + query.Encode()

	var lastStatus int
	var lastBody string
	delay := c.retry.base
	for attempt := 0; attempt <= c.retry.attempts; attempt++ {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
		if err != nil {
			return nil, fmt.Errorf("tmdb: build request: %w", err)
		}
		req.Header.Set("Accept", "application/json")
		resp, err := c.httpClient.Do(req)
		if err != nil {
			// Network errors count as "retriable" in the same sense as 5xx.
			if attempt == c.retry.attempts {
				return nil, fmt.Errorf("tmdb: transport: %w", err)
			}
			if err := sleepCtx(ctx, delay); err != nil {
				return nil, err
			}
			delay *= time.Duration(c.retry.factor)
			continue
		}
		body, _ := io.ReadAll(resp.Body)
		_ = resp.Body.Close()

		switch {
		case resp.StatusCode >= 200 && resp.StatusCode < 300:
			return body, nil
		case resp.StatusCode == http.StatusNotFound:
			return nil, ErrNotFound
		case resp.StatusCode == http.StatusTooManyRequests || resp.StatusCode >= 500:
			lastStatus = resp.StatusCode
			lastBody = truncate(string(body), 256)
			if attempt == c.retry.attempts {
				return nil, &ErrUpstream{Status: lastStatus, Body: lastBody}
			}
			if err := sleepCtx(ctx, delay); err != nil {
				return nil, err
			}
			delay *= time.Duration(c.retry.factor)
		default:
			// 4xx other than 404 and 429 — won't change on retry.
			return nil, &ErrUpstream{Status: resp.StatusCode, Body: truncate(string(body), 256)}
		}
	}
	return nil, &ErrUpstream{Status: lastStatus, Body: lastBody}
}

func pathFor(kind Kind, id int) (string, error) {
	switch kind {
	case KindMovie:
		return fmt.Sprintf("/movie/%d", id), nil
	case KindSeries:
		return fmt.Sprintf("/tv/%d", id), nil
	default:
		return "", fmt.Errorf("tmdb: unknown kind %q", kind)
	}
}

func fromMediaType(mt string) (Kind, bool) {
	switch mt {
	case "movie":
		return KindMovie, true
	case "tv":
		return KindSeries, true
	default:
		return "", false
	}
}

// yearOf parses TMDB's YYYY-MM-DD date string and returns the year. Empty
// or malformed dates collapse to 0.
func yearOf(date string) int {
	if len(date) < 4 {
		return 0
	}
	y, err := strconv.Atoi(date[:4])
	if err != nil {
		return 0
	}
	return y
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}

func sleepCtx(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}
