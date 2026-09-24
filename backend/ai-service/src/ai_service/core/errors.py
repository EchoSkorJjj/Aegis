"""Domain exceptions and handlers — same error body shape as the Java services
({timestamp, status, error, message, path})."""

from datetime import UTC, datetime

from fastapi import Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

_REASON = {
    400: "Bad Request", 401: "Unauthorized", 422: "Unprocessable Entity",
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


def _body(status: int, message: str, path: str) -> dict:
    return {
        "timestamp": datetime.now(UTC).isoformat(),
        "status": status,
        "error": _REASON.get(status, "Error"),
        "message": message,
        "path": path,
    }


async def api_error_handler(request: Request, exc: ApiError) -> JSONResponse:
    return JSONResponse(status_code=exc.status_code, content=_body(exc.status_code, exc.message, request.url.path))


async def validation_error_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    message = "; ".join(
        f"{'.'.join(str(loc) for loc in err['loc'][1:])} {err['msg']}".strip() for err in exc.errors()
    )
    return JSONResponse(status_code=400, content=_body(400, message or "Validation failed", request.url.path))
