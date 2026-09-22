package com.aegis.core.platform.common;

/** Domain exceptions mapped to HTTP status codes in {@link GlobalExceptionHandler}. */
public final class ApiExceptions {

    private ApiExceptions() {
    }

    public static class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }

    public static class Conflict extends RuntimeException {
        public Conflict(String message) {
            super(message);
        }
    }

    public static class BadRequest extends RuntimeException {
        public BadRequest(String message) {
            super(message);
        }
    }

    public static class Unauthorized extends RuntimeException {
        public Unauthorized(String message) {
            super(message);
        }
    }

    public static class Forbidden extends RuntimeException {
        public Forbidden(String message) {
            super(message);
        }
    }

    /** The ai-service was unreachable or returned an error. */
    public static class UpstreamUnavailable extends RuntimeException {
        public UpstreamUnavailable(String message) {
            super(message);
        }
    }
}
