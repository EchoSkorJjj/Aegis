"""Service-to-service auth: a shared token in the X-Service-Token header.

aegis-core is the only caller; it presents this token. No user JWTs here — the ai-service is a
compute dependency and stays ignorant of users. (mTLS or a dedicated service identity can replace
the shared token later without changing callers.)
"""

import hmac

from fastapi import Header

from ai_service.core.config import settings
from ai_service.core.errors import Unauthorized


def require_service_token(x_service_token: str | None = Header(default=None, alias="X-Service-Token")) -> None:
    # Constant-time comparison to avoid leaking the token via timing.
    if not x_service_token or not hmac.compare_digest(x_service_token, settings.service_token):
        raise Unauthorized("Missing or invalid service token")
