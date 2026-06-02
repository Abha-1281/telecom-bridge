package com.telecom.gateway.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GlobalExceptionHandler — Catches all unhandled exceptions and returns
 * clean, structured JSON error responses.
 *
 * WHY DO WE NEED THIS?
 *
 * Without this, Spring WebFlux returns ugly default error pages or
 * stack traces in JSON that expose internals. This handler:
 *   - Returns consistent JSON structure for ALL error types
 *   - Maps Spring validation errors → 400 with field-level details
 *   - Prevents stack traces from leaking to API consumers
 *
 * HANDLED ERRORS:
 *   WebExchangeBindException → 400 (from @Valid on request body)
 *   Everything else         → 500 (fallback, shouldn't happen)
 *
 * NOTE: TimeoutException and DiameterException are already handled
 * inside ChargeController per-endpoint, not here.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles @Valid validation failures on @RequestBody.
     *
     * When ChargeRequest fails validation (e.g., msisdn is blank, units ≤ 0),
     * Spring throws WebExchangeBindException. We convert it to a readable
     * JSON response with per-field error messages.
     *
     * Example response:
     * {
     *   "status": 400,
     *   "error": "Validation Failed",
     *   "timestamp": "2024-01-01T00:00:00Z",
     *   "fields": {
     *     "msisdn": "must not be blank",
     *     "requestedUnits": "must be greater than 0"
     *   }
     * }
     */
    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleValidationException(
            WebExchangeBindException ex,
            ServerWebExchange exchange) {

        // Collect field-level error messages (field name → error message)
        Map<String, String> fieldErrors = ex.getBindingResult()
            .getAllErrors()
            .stream()
            .filter(err -> err instanceof FieldError)
            .map(err -> (FieldError) err)
            .collect(Collectors.toMap(
                FieldError::getField,
                fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid",
                (existing, replacement) -> existing  // keep first if duplicate field
            ));

        log.warn("❌ Validation failed on {}: {}", exchange.getRequest().getPath(), fieldErrors);

        Map<String, Object> body = new HashMap<>();
        body.put("status", 400);
        body.put("error", "Validation Failed");
        body.put("timestamp", Instant.now().toString());
        body.put("path", exchange.getRequest().getPath().value());
        body.put("fields", fieldErrors);

        return Mono.just(ResponseEntity.badRequest().body(body));
    }

    /**
     * Fallback handler for any unexpected exception not handled elsewhere.
     * Returns HTTP 500 with minimal information (no stack traces exposed).
     */
    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleGenericException(
            Exception ex,
            ServerWebExchange exchange) {

        log.error("❌ Unhandled exception on {}: {}", exchange.getRequest().getPath(),
                  ex.getMessage(), ex);

        Map<String, Object> body = new HashMap<>();
        body.put("status", 500);
        body.put("error", "Internal Server Error");
        body.put("timestamp", Instant.now().toString());
        body.put("path", exchange.getRequest().getPath().value());

        return Mono.just(ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(body));
    }
}
