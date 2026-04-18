package httpx

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"net/http"
)

const (
	csrfCookieName  = "ENTLIB_CSRF"
	csrfHeaderName  = "X-CSRF-Token"
	csrfTokenBytes  = 32
	csrfCookieMaxAge = 60 * 60 * 24 * 365
)

func CSRFMiddleware(next http.Handler, secure bool) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token, err := readOrMintCSRF(r, w, secure)
		if err != nil {
			http.Error(w, "csrf: token init", http.StatusInternalServerError)
			return
		}

		if isMutatingMethod(r.Method) {
			header := r.Header.Get(csrfHeaderName)
			if header == "" || subtle.ConstantTimeCompare([]byte(header), []byte(token)) != 1 {
				http.Error(w, "csrf: token mismatch", http.StatusForbidden)
				return
			}
		}

		next.ServeHTTP(w, r)
	})
}

func isMutatingMethod(m string) bool {
	switch m {
	case http.MethodPost, http.MethodPut, http.MethodPatch, http.MethodDelete:
		return true
	}
	return false
}

func readOrMintCSRF(r *http.Request, w http.ResponseWriter, secure bool) (string, error) {
	if c, err := r.Cookie(csrfCookieName); err == nil && c.Value != "" {
		return c.Value, nil
	}
	token, err := newCSRFToken()
	if err != nil {
		return "", err
	}
	http.SetCookie(w, &http.Cookie{
		Name:     csrfCookieName,
		Value:    token,
		Path:     "/",
		MaxAge:   csrfCookieMaxAge,
		HttpOnly: false, // SPA reads this via document.cookie per ADR 0005
		Secure:   secure,
		SameSite: http.SameSiteLaxMode,
	})
	return token, nil
}

func newCSRFToken() (string, error) {
	b := make([]byte, csrfTokenBytes)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}
