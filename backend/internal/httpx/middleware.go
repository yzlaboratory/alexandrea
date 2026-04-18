package httpx

import (
	"context"
	"net/http"

	"github.com/yzlaboratory/entertainment-library/backend/internal/auth"
)

// ctxKey is unexported so callers can't collide with our context keys.
type ctxKey string

const sessionCtxKey ctxKey = "entlib.session"

// RequireAuth wraps next with a session-cookie check. On success the
// authenticated *auth.Session is attached to the request context so downstream
// handlers can resolve the user without re-reading the cookie.
//
// Failures return 401 with a uniform JSON body. Successful loads also touch
// the session (sliding window) — a best-effort call whose failure never
// locks the user out.
func RequireAuth(d Deps, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := r.Cookie(sessionCookieName)
		if err != nil || c.Value == "" {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "no session"})
			return
		}
		sess, err := d.Sessions.Load(c.Value)
		if err != nil {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "no session"})
			return
		}
		if _, err := d.Sessions.Touch(sess); err != nil {
			// Touch is best-effort; log-and-continue would go here once a
			// logger is plumbed through Deps.
			_ = err
		}
		ctx := context.WithValue(r.Context(), sessionCtxKey, sess)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// SessionFromContext returns the session attached by RequireAuth, or a zero
// Session and false if the request did not pass through RequireAuth.
func SessionFromContext(ctx context.Context) (auth.Session, bool) {
	s, ok := ctx.Value(sessionCtxKey).(auth.Session)
	return s, ok
}
