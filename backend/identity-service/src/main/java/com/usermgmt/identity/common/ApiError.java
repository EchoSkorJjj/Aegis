package com.usermgmt.identity.common;

import java.time.Instant;

/** Uniform error body returned by {@link GlobalExceptionHandler}. */
public record ApiError(Instant timestamp, int status, String error, String message, String path) {
}
