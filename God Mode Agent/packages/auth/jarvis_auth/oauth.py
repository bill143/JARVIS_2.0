"""OAuth-ready abstraction with Google/GitHub provider stubs.

Real token exchange requires client credentials and network access; these
stubs define the interface and a deterministic mock exchange so the flow is
testable offline. Wire real HTTP calls in exchange_code when credentials exist.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass
class OAuthProfile:
    provider: str
    subject: str
    email: str
    username: str


class OAuthProvider(ABC):
    name = "base"
    authorize_url = ""
    token_url = ""

    @abstractmethod
    def exchange_code(self, code: str) -> OAuthProfile:
        """Exchange an authorization code for a normalized profile."""


class GoogleOAuthProvider(OAuthProvider):
    name = "google"
    authorize_url = "https://accounts.google.com/o/oauth2/v2/auth"
    token_url = "https://oauth2.googleapis.com/token"

    def exchange_code(self, code: str) -> OAuthProfile:
        # Stub: deterministic offline profile derived from the code.
        subject = f"google-{code[:12]}"
        return OAuthProfile(provider=self.name, subject=subject, email=f"{subject}@example.com", username=subject)


class GitHubOAuthProvider(OAuthProvider):
    name = "github"
    authorize_url = "https://github.com/login/oauth/authorize"
    token_url = "https://github.com/login/oauth/access_token"

    def exchange_code(self, code: str) -> OAuthProfile:
        subject = f"github-{code[:12]}"
        return OAuthProfile(provider=self.name, subject=subject, email=f"{subject}@example.com", username=subject)


_PROVIDERS = {"google": GoogleOAuthProvider(), "github": GitHubOAuthProvider()}


def get_oauth_provider(name: str) -> OAuthProvider | None:
    return _PROVIDERS.get(name)
