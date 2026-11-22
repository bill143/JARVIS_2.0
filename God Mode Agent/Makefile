# Windows-first: every target shells out to the authoritative PowerShell scripts.
PWSH ?= pwsh -NoProfile -ExecutionPolicy Bypass -File

.PHONY: setup dev-api dev-web dev-desktop dev test lint format

setup:
	$(PWSH) scripts/setup.ps1

dev-api:
	$(PWSH) scripts/dev-api.ps1

dev-web:
	$(PWSH) scripts/dev-web.ps1

dev-desktop:
	$(PWSH) scripts/dev-desktop.ps1

dev:
	$(PWSH) scripts/dev-all.ps1

test:
	$(PWSH) scripts/test.ps1

lint:
	$(PWSH) scripts/lint.ps1

format:
	$(PWSH) scripts/format.ps1
