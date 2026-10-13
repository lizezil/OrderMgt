# Order Assistant -- one entry point for the tasks you actually run.
#
#   make            same as `make help`
#   make dev        backend on :8081 against the resident MySQL
#   make test       backend test suite (needs the resident MySQL)
#   make test-frontend  SPA unit + component tests (Vitest)
#   make test-e2e   browser suite (Playwright + Chromium; needs the resident MySQL)
#
# Maven is always invoked through the wrapper, so the checked-in version is what runs.
# `JAVA_HOME=/path/to/jdk17 make ...` overrides the JDK if the default is not 17.

SHELL := /bin/bash
.DEFAULT_GOAL := help

# Load .env (git-ignored) so every target -- dev, test, e2e -- picks up
# DASHSCOPE_API_KEY and MYSQL_* without exporting them by hand. A real environment variable
# still wins: make does not override variables that are already set.
-include .env
export

MVNW       := ./mvnw
PNPM       := pnpm
BACKEND    := -pl backend -am

.PHONY: help dev dev-frontend install build build-backend build-frontend \
        test test-frontend test-e2e format check db-up docker-up docker-down docker-logs clean

help: ## List the available targets
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

# --- local development -------------------------------------------------------

dev: ## Run the backend against the resident MySQL (http://localhost:8081)
	$(MVNW) $(BACKEND) spring-boot:run

dev-frontend: ## Run the Vite dev server (http://localhost:5173, proxies /api to :8081)
	cd frontend && $(PNPM) dev

install: ## Install the frontend dependencies
	cd frontend && $(PNPM) install

# --- build -------------------------------------------------------------------

build: ## Package the jar with the SPA baked in (requires Node/pnpm)
	$(MVNW) -Pfrontend package

build-backend: ## Package the backend jar only (no Node needed)
	$(MVNW) $(BACKEND) package

build-frontend: ## Build the SPA into backend/src/main/resources/static
	cd frontend && $(PNPM) build

# --- verify ------------------------------------------------------------------

test: ## Run the backend test suite (needs the resident MySQL running)
	$(MVNW) $(BACKEND) test

test-frontend: ## Run the SPA unit and component tests (Vitest, jsdom)
	cd frontend && $(PNPM) test

test-e2e: ## Run the browser suite (Playwright + Chromium; builds the SPA and boots the app itself)
	cd frontend && $(PNPM) test:e2e

format: ## Rewrite formatting: Spotless for Java, Prettier for the frontend
	$(MVNW) $(BACKEND) spotless:apply
	cd frontend && $(PNPM) format

check: ## Verify formatting without rewriting anything (CI-friendly)
	$(MVNW) $(BACKEND) spotless:check
	cd frontend && $(PNPM) format:check

# --- containers --------------------------------------------------------------

db-up: ## Start just the resident MySQL container (localhost:3306); dev and tests need it
	docker compose up -d mysql

docker-up: ## Build and start MySQL + app via Compose
	docker compose up --build

docker-down: ## Stop and remove the Compose stack
	docker compose down

docker-logs: ## Follow the app container's logs
	docker compose logs -f app

# --- housekeeping ------------------------------------------------------------

clean: ## Remove build output (target/ and the built SPA assets)
	$(MVNW) clean
	rm -rf frontend/dist backend/src/main/resources/static/assets \
		backend/src/main/resources/static/index.html
