package httpx

import (
	"context"
	"database/sql"

	"github.com/yzlaboratory/entertainment-library/backend/internal/auth"
	"github.com/yzlaboratory/entertainment-library/backend/internal/tmdb"
)

// TMDBSearcher is the narrow surface of *tmdb.Client the handlers use. The
// interface exists so tests can stand in a fake without instantiating a
// real http client.
type TMDBSearcher interface {
	Search(ctx context.Context, query string) ([]tmdb.Result, error)
	Get(ctx context.Context, kind tmdb.Kind, id int) (tmdb.Result, error)
}

type Deps struct {
	DB           *sql.DB
	Sessions     *auth.SessionStore
	CookieSecure bool
	TMDB         TMDBSearcher
}
