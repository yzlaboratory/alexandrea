.PHONY: build build-frontend build-backend test test-frontend test-backend dev clean

DIST_SRC := frontend/dist
DIST_DST := backend/internal/web/dist

build: build-frontend build-backend

build-frontend:
	cd frontend && pnpm install --frozen-lockfile
	cd frontend && pnpm build
	rm -rf $(DIST_DST)
	mkdir -p $(DIST_DST)
	cp -R $(DIST_SRC)/. $(DIST_DST)/
	# Preserve the sentinel so `go build` still works on a fresh checkout.
	touch $(DIST_DST)/.gitkeep

build-backend:
	cd backend && go build -o ../entlib ./cmd/entlib

test: test-frontend test-backend

test-frontend:
	cd frontend && pnpm test

test-backend:
	cd backend && go test ./...

dev:
	@echo "Start two terminals:"
	@echo "  terminal 1: (cd backend && ENTLIB_COOKIE_SECURE=false go run ./cmd/entlib)"
	@echo "  terminal 2: (cd frontend && pnpm dev)"
	@echo "Frontend: http://127.0.0.1:5173  (proxies /api, /login, /logout to :8080)"

clean:
	rm -rf frontend/dist frontend/node_modules $(DIST_DST) entlib
	touch $(DIST_DST)/.gitkeep
