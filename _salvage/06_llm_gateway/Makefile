.PHONY: help dev up down logs test lint scan deploy-check clean

help:
	@echo "ECHO LiteLLM Proxy - dev commands"
	@echo ""
	@echo "  make dev           Run locally with hot reload (no Docker)"
	@echo "  make up            docker compose up -d (full stack: ECHO + Langfuse + Postgres)"
	@echo "  make down          docker compose down"
	@echo "  make logs          Tail ECHO container logs"
	@echo "  make test          pytest"
	@echo "  make lint          ruff + mypy"
	@echo "  make scan          gitleaks + trufflehog"
	@echo "  make deploy-check  Pre-deploy validation (lint + test + scan + build)"
	@echo "  make clean         Remove .venv, caches, __pycache__"

dev:
	uv run uvicorn app.main:app --reload --port 4000

up:
	docker compose up -d

down:
	docker compose down

logs:
	docker compose logs -f echo

test:
	uv run pytest -v

lint:
	uv run ruff check .
	uv run mypy app

scan:
	bash scripts/scan-secrets.sh

deploy-check: lint test scan
	docker build --target runtime -t echo:precheck .
	@echo "OK - Ready to deploy"

clean:
	rm -rf .venv .pytest_cache .ruff_cache __pycache__ **/__pycache__
