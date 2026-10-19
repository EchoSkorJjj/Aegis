"""FastAPI application factory for the AI service."""

from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError

from ai_service.api.routes import router
from ai_service.core.config import Settings, settings
from ai_service.core.errors import ApiError, api_error_handler, validation_error_handler
from ai_service.core.observability import setup_observability
from ai_service.llm.base import LlmClient
from ai_service.llm.factory import get_llm_client
from ai_service.llm.structured import StructuredGenerator


def create_app(config: Settings | None = None, *, llm_client: LlmClient | None = None) -> FastAPI:
    config = config or settings
    # Validate provider config during app construction, before accepting requests.
    provider = llm_client or get_llm_client(config)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        try:
            yield
        finally:
            await provider.aclose()

    app = FastAPI(
        title="Aegis AI Service",
        version="v1",
        description="Internal assessment and drafting service. Called with a service token.",
        lifespan=lifespan,
    )
    app.state.settings = config
    app.state.generator = StructuredGenerator(config, provider)
    # No CORS: the service is internal-only and never called from a browser.
    app.add_exception_handler(ApiError, api_error_handler)
    app.add_exception_handler(RequestValidationError, validation_error_handler)
    app.include_router(router)
    setup_observability(app, config)
    return app


app = create_app()
