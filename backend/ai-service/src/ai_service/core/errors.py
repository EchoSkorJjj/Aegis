"""Domain exceptions and handlers — same error body shape as the Java services
({timestamp, status, error, message, path})."""

from datetime import UTC, datetime

from fastapi import Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

_REASON = {
    400: "Bad Request", 401: "Unauthorized", 422: "Unprocessable Entity", 429: "Too Many Requests",
    500: "Internal Server Error", 502: "Bad Gateway", 503: "Service Unavailable",
}


class ApiError(Exception):
    status_code = 500

    def __init__(self, message: str):
        self.message = message
        super().__init__(message)


class BadRequest(ApiError):
    status_code = 400


class Unauthorized(ApiError):
    status_code = 401


class BadGateway(ApiError):
    status_code = 502


class ServiceUnavailable(ApiError):
    status_code = 503


class RateLimited(ServiceUnavailable):
    """A per-minute limit is full now, but will have room in ``retry_after`` seconds: the caller may wait and retry."""

    status_code = 429

    def __init__(self, message: str, *, retry_after: int):
        super().__init__(message)
        self.retry_after = retry_after


def _body(status: int, message: str, path: str) -> dict:
    return {
        "timestamp": datetime.now(UTC).isoformat(),
        "status": status,
        "error": _REASON.get(status, "Error"),
        "message": message,
        "path": path,
    }


async def api_error_handler(request: Request, exc: ApiError) -> JSONResponse:
    headers = {"Retry-After": str(exc.retry_after)} if isinstance(exc, RateLimited) else None
    return JSONResponse(status_code=exc.status_code, content=_body(exc.status_code, exc.message, request.url.path),
                        headers=headers)


async def validation_error_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    # Unknown field names and validation details can themselves contain sensitive/untrusted input.
    return JSONResponse(status_code=400, content=_body(400, "Request validation failed", request.url.path))
