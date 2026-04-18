package httpx

import "net/http"

func NewRouter() http.Handler {
	mux := http.NewServeMux()
	mux.Handle("GET /api/health", Health())
	return mux
}
