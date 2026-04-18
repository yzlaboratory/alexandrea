package httpx

import "net/http"

func NewRouter(d Deps) http.Handler {
	mux := http.NewServeMux()
	mux.Handle("GET /api/health", Health())
	if d.Sessions != nil {
		mux.Handle("POST /login", Login(d))
		mux.Handle("POST /logout", Logout(d))
		mux.Handle("GET /api/me", Me(d))
	}
	return mux
}
