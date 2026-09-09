"""FastAPI application factory for the AI service."""

from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError

from ai_service.api.routes import router
from ai_service.core.errors import ApiError, api_error_handler, validation_error_handler
from ai_service.core.observability import setup_observability


def create_app() -> FastAPI:
    app = FastAPI(
        title="Aegis AI Service",
        version="v1",
        description="Internal AI service — assess + draft (RAG). Called by aegis-core with a service token.",
    )
    # No CORS: the service is internal-only and never called from a browser.
    app.add_exception_handler(ApiError, api_error_handler)
    app.add_exception_handler(RequestValidationError, validation_error_handler)
    app.include_router(router)
    setup_observability(app)
    return app


app = create_app()
