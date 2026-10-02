package app.seats;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** A domain outcome that maps to a 4xx. Thrown anywhere; rendered as {"error": code, "message": ...}. */
public class ApiError extends RuntimeException {
    final HttpStatus status;
    final String code;

    ApiError(HttpStatus status, String code, String message) {
        // Expected 4xx outcomes are control flow; collecting a stack for every
        // losing contender wastes CPU during hot-seat bursts. Unexpected errors
        // remain ordinary exceptions with full diagnostic stacks.
        super(message, null, false, false);
        this.status = status;
        this.code = code;
    }

    static ApiError badRequest(String msg) { return new ApiError(HttpStatus.BAD_REQUEST, "bad_request", msg); }
    static ApiError notFound(String msg) { return new ApiError(HttpStatus.NOT_FOUND, "not_found", msg); }
    static ApiError unauthorized() { return new ApiError(HttpStatus.UNAUTHORIZED, "unauthorized", "missing or invalid token"); }
    static ApiError conflict(String code, String msg) { return new ApiError(HttpStatus.CONFLICT, code, msg); }

    @RestControllerAdvice
    static class Handler {
        @ExceptionHandler(ApiError.class)
        ResponseEntity<Map<String, String>> handle(ApiError e) {
            return ResponseEntity.status(e.status).body(Map.of("error", e.code, "message", e.getMessage()));
        }
    }
}
