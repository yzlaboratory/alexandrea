package httpx

import (
	"database/sql"

	"github.com/yzlaboratory/entertainment-library/backend/internal/auth"
)

type Deps struct {
	DB           *sql.DB
	Sessions     *auth.SessionStore
	CookieSecure bool
}
