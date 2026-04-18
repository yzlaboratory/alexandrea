package tmdb

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// newTestServer spins up an httptest.Server whose handler is `h`, and a
// *Client wired to that server with tiny retry delays so failure-path tests
// stay fast.
func newTestServer(t *testing.T, h http.HandlerFunc) (*httptest.Server, *Client) {
	t.Helper()
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	c := New("test-api-key", Options{BaseURL: srv.URL, RetryBase: time.Microsecond})
	return srv, c
}

func TestSearch_FiltersAndShapesResults(t *testing.T) {
	body := `{
	  "results": [
	    {"id": 1, "media_type": "movie", "title": "The Movie", "release_date": "2019-05-01", "poster_path": "/a.jpg", "overview": "m-overview"},
	    {"id": 2, "media_type": "tv",    "name":  "The Show",  "first_air_date": "2020-01-01", "poster_path": "/b.jpg", "overview": "s-overview"},
	    {"id": 3, "media_type": "person","name":  "Some Actor"}
	  ]
	}`
	_, c := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		if got := r.URL.Query().Get("query"); got != "the" {
			t.Errorf("query = %q, want 'the'", got)
		}
		if got := r.URL.Query().Get("api_key"); got != "test-api-key" {
			t.Errorf("api_key not forwarded")
		}
		if got := r.URL.Query().Get("language"); got != "en-US" {
			t.Errorf("language = %q, want en-US", got)
		}
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, body)
	})

	out, err := c.Search(context.Background(), "the")
	if err != nil {
		t.Fatalf("search: %v", err)
	}
	if len(out) != 2 {
		t.Fatalf("len = %d, want 2 (person filtered)", len(out))
	}
	if out[0].Kind != KindMovie || out[0].Title != "The Movie" || out[0].Year != 2019 {
		t.Errorf("movie shape wrong: %+v", out[0])
	}
	if out[1].Kind != KindSeries || out[1].Title != "The Show" || out[1].Year != 2020 {
		t.Errorf("series shape wrong: %+v", out[1])
	}
}

func TestSearch_EmptyQueryRejected(t *testing.T) {
	c := New("k", Options{BaseURL: "http://unused"})
	if _, err := c.Search(context.Background(), ""); err == nil {
		t.Fatal("empty query must return error")
	}
}

func TestGet_MovieShape(t *testing.T) {
	_, c := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		if got := r.URL.Path; got != "/movie/42" {
			t.Errorf("path = %q, want /movie/42", got)
		}
		fmt.Fprint(w, `{"id":42,"title":"Answer","release_date":"2005-06-07","poster_path":"/p.jpg","overview":"o"}`)
	})
	r, err := c.Get(context.Background(), KindMovie, 42)
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	want := Result{TMDBID: 42, Kind: KindMovie, Title: "Answer", Year: 2005, PosterPath: "/p.jpg", Overview: "o"}
	if r != want {
		t.Errorf("got %+v, want %+v", r, want)
	}
}

func TestGet_SeriesShape(t *testing.T) {
	_, c := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		if got := r.URL.Path; got != "/tv/99" {
			t.Errorf("path = %q, want /tv/99", got)
		}
		fmt.Fprint(w, `{"id":99,"name":"Show","first_air_date":"2010-02-03","poster_path":"/s.jpg","overview":"so"}`)
	})
	r, err := c.Get(context.Background(), KindSeries, 99)
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	want := Result{TMDBID: 99, Kind: KindSeries, Title: "Show", Year: 2010, PosterPath: "/s.jpg", Overview: "so"}
	if r != want {
		t.Errorf("got %+v, want %+v", r, want)
	}
}

func TestGet_404MapsToErrNotFound(t *testing.T) {
	_, c := newTestServer(t, func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		fmt.Fprint(w, `{"status_message":"no"}`)
	})
	_, err := c.Get(context.Background(), KindMovie, 12345)
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("err = %v, want ErrNotFound", err)
	}
}

func TestGet_InvalidKindRejected(t *testing.T) {
	c := New("k", Options{BaseURL: "http://unused"})
	if _, err := c.Get(context.Background(), Kind("bogus"), 1); err == nil {
		t.Fatal("invalid kind must return error")
	}
}

func TestGet_InvalidIDRejected(t *testing.T) {
	c := New("k", Options{BaseURL: "http://unused"})
	if _, err := c.Get(context.Background(), KindMovie, 0); err == nil {
		t.Fatal("zero id must return error")
	}
}

func TestRetry_On429ThenSuccess(t *testing.T) {
	var calls atomic.Int32
	_, c := newTestServer(t, func(w http.ResponseWriter, _ *http.Request) {
		n := calls.Add(1)
		if n <= 2 {
			w.WriteHeader(http.StatusTooManyRequests)
			return
		}
		fmt.Fprint(w, `{"id":1,"title":"ok","release_date":"2001-01-01"}`)
	})
	r, err := c.Get(context.Background(), KindMovie, 1)
	if err != nil {
		t.Fatalf("after retries: %v", err)
	}
	if calls.Load() != 3 {
		t.Errorf("calls = %d, want 3 (two 429s + success)", calls.Load())
	}
	if r.Title != "ok" {
		t.Errorf("result title = %q", r.Title)
	}
}

func TestRetry_ExhaustsOn5xx(t *testing.T) {
	var calls atomic.Int32
	_, c := newTestServer(t, func(w http.ResponseWriter, _ *http.Request) {
		calls.Add(1)
		w.WriteHeader(http.StatusInternalServerError)
		fmt.Fprint(w, `boom`)
	})
	_, err := c.Get(context.Background(), KindMovie, 1)
	var upstream *ErrUpstream
	if !errors.As(err, &upstream) {
		t.Fatalf("err = %v, want *ErrUpstream", err)
	}
	if upstream.Status != http.StatusInternalServerError {
		t.Errorf("status = %d, want 500", upstream.Status)
	}
	// 1 initial + 3 retries = 4 attempts.
	if got := calls.Load(); got != 4 {
		t.Errorf("calls = %d, want 4", got)
	}
}

func TestRetry_4xxIsTerminalWithoutRetry(t *testing.T) {
	var calls atomic.Int32
	_, c := newTestServer(t, func(w http.ResponseWriter, _ *http.Request) {
		calls.Add(1)
		w.WriteHeader(http.StatusUnauthorized)
	})
	_, err := c.Get(context.Background(), KindMovie, 1)
	var upstream *ErrUpstream
	if !errors.As(err, &upstream) {
		t.Fatalf("err = %v, want *ErrUpstream", err)
	}
	if got := calls.Load(); got != 1 {
		t.Errorf("calls = %d, want 1 (no retry on 401)", got)
	}
}

func TestSingleflight_DedupesInflightCalls(t *testing.T) {
	var calls atomic.Int32
	release := make(chan struct{})
	_, c := newTestServer(t, func(w http.ResponseWriter, _ *http.Request) {
		calls.Add(1)
		<-release // Hold the first request open until both goroutines have started.
		fmt.Fprint(w, `{"results":[{"id":1,"media_type":"movie","title":"x","release_date":"2000-01-01"}]}`)
	})

	var wg sync.WaitGroup
	var r1, r2 []Result
	var e1, e2 error
	wg.Add(2)
	go func() { defer wg.Done(); r1, e1 = c.Search(context.Background(), "same") }()
	go func() { defer wg.Done(); r2, e2 = c.Search(context.Background(), "same") }()

	// Give both goroutines a moment to enter singleflight.Do before we
	// release the in-flight call.
	time.Sleep(30 * time.Millisecond)
	close(release)
	wg.Wait()

	if e1 != nil || e2 != nil {
		t.Fatalf("errors: %v / %v", e1, e2)
	}
	if calls.Load() != 1 {
		t.Errorf("upstream calls = %d, want 1 (singleflight should dedupe)", calls.Load())
	}
	if len(r1) != 1 || len(r2) != 1 {
		t.Errorf("both callers must get results: %d / %d", len(r1), len(r2))
	}
}

func TestRetry_RespectsContextCancellation(t *testing.T) {
	_, c := newTestServer(t, func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	})
	// Use a retry base long enough that the cancel will land between attempts.
	c.retry.base = 100 * time.Millisecond

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		time.Sleep(20 * time.Millisecond)
		cancel()
	}()
	_, err := c.Get(ctx, KindMovie, 1)
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v, want context.Canceled", err)
	}
}
