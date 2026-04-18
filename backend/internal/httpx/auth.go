package httpx

import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"

	"github.com/yzlaboratory/entertainment-library/backend/internal/auth"
)

const (
	sessionCookieName = "ENTLIB_SESSION"
	sessionCookiePath = "/"
)

func sessionCookieMaxAgeSeconds() int {
	return int(auth.SessionLifetime.Seconds())
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func genericAuthFailure(w http.ResponseWriter) {
	writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "sign-in failed"})
}

func Login(d Deps) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseForm(); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad form"})
			return
		}
		username := r.PostFormValue("username")
		password := r.PostFormValue("password")
		if username == "" || password == "" {
			genericAuthFailure(w)
			return
		}

		var hash string
		err := d.DB.QueryRow(
			`SELECT c.password_hash FROM user_credential c JOIN "user" u ON u.id = c.user_id WHERE u.id = ?`,
			username,
		).Scan(&hash)
		if errors.Is(err, sql.ErrNoRows) {
			// Still run a dummy verify to equalise timing across unknown-user vs. wrong-password paths.
			_ = auth.VerifyPassword(password, dummyHash)
			genericAuthFailure(w)
			return
		}
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "server error"})
			return
		}

		if err := auth.VerifyPassword(password, hash); err != nil {
			genericAuthFailure(w)
			return
		}

		sess, err := d.Sessions.Mint(username)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "session mint"})
			return
		}
		http.SetCookie(w, &http.Cookie{
			Name:     sessionCookieName,
			Value:    sess.ID,
			Path:     sessionCookiePath,
			MaxAge:   sessionCookieMaxAgeSeconds(),
			HttpOnly: true,
			Secure:   d.CookieSecure,
			SameSite: http.SameSiteLaxMode,
		})
		w.WriteHeader(http.StatusNoContent)
	})
}

func Logout(d Deps) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if c, err := r.Cookie(sessionCookieName); err == nil {
			_ = d.Sessions.Delete(c.Value)
		}
		http.SetCookie(w, &http.Cookie{
			Name:     sessionCookieName,
			Value:    "",
			Path:     sessionCookiePath,
			MaxAge:   -1,
			HttpOnly: true,
			Secure:   d.CookieSecure,
			SameSite: http.SameSiteLaxMode,
		})
		w.WriteHeader(http.StatusNoContent)
	})
}

func Me(d Deps) http.Handler {
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
			// Log and continue — a failed touch shouldn't lock the user out.
		}

		var displayName string
		if err := d.DB.QueryRow(`SELECT display_name FROM "user" WHERE id = ?`, sess.UserID).Scan(&displayName); err != nil {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "no session"})
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{
			"user_id":      sess.UserID,
			"display_name": displayName,
		})
	})
}

// dummyHash is used so that unknown-username paths still incur an argon2
// verify cost, equalising response time against the wrong-password path.
// Generated once at init() to avoid embedding a fixed hash that varies by
// parameters; regenerated if the default params change.
var dummyHash string

func init() {
	h, err := auth.HashPassword("unused-dummy-password-for-timing")
	if err != nil {
		// init() failures are programmer errors; panic is appropriate.
		panic("precompute dummy hash: " + err.Error())
	}
	dummyHash = h
}
