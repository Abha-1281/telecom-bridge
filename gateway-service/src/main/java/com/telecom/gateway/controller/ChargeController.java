package com.telecom.gateway.controller;

import com.telecom.gateway.diameter.client.DiameterException;
import com.telecom.gateway.model.diameter.AvpCodes;
import com.telecom.gateway.model.diameter.DiameterMessage;
import com.telecom.gateway.model.rest.ChargeRequest;
import com.telecom.gateway.model.rest.ChargeResponse;
import com.telecom.gateway.service.ChargeService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * ChargeController — The REST API entry point.
 *
 * Exposes: POST /api/v1/charge
 *
 * RETURNS Mono<ResponseEntity<>> — NEVER BLOCKS.
 *
 * WebFlux (reactive) vs MVC (traditional):
 *
 * MVC (blocking):
 *   Thread 1 → handles Request A → waits 70ms for Diameter → responds → free
 *   Thread 2 → handles Request B → waits 70ms for Diameter → responds → free
 *   At 100 TPS, need 100+ threads just sitting idle waiting
 *
 * WebFlux (non-blocking):
 *   Thread 1 → handles Request A → sends Diameter → IMMEDIATELY handles Request B
 *   Thread 1 → handles Request B → sends Diameter → IMMEDIATELY handles Request C
 *   When Diameter responds to A → Thread 1 (or 2) completes Response A
 *   Same 2 threads handle 100+ concurrent requests!
 *
 * ERROR MAPPING:
 *   TimeoutException     → HTTP 504 Gateway Timeout
 *   DiameterException    → HTTP 503 Service Unavailable
 *   ValidationException  → HTTP 400 Bad Request
 */
@RestController
@RequestMapping("/api/v1")
public class ChargeController {

    private static final Logger log = LoggerFactory.getLogger(ChargeController.class);

    private final ChargeService chargeService;

    public ChargeController(ChargeService chargeService) {
        this.chargeService = chargeService;
    }

    /**
     * POST /api/v1/charge
     *
     * Accepts a charging request, translates to Diameter CCR,
     * awaits CCA response asynchronously, and returns JSON result.
     *
     * @Valid: Triggers Bean Validation on ChargeRequest fields before
     *         the method even executes. Returns HTTP 400 if validation fails.
     *
     * Mono<ResponseEntity<ChargeResponse>>: The reactive type that tells
     *   Spring WebFlux "don't block, I'll give you the response when ready."
     */
    @PostMapping("/charge")
    public Mono<ResponseEntity<ChargeResponse>> charge(
            @Valid @RequestBody ChargeRequest request) {

        log.info("📥 Charge request received: msisdn={} units={} currency={}",
                 maskMsisdn(request.getMsisdn()),
                 request.getRequestedUnits(),
                 request.getCurrency());

        return chargeService.processCharge(request)
            // On success: wrap in HTTP 200
            .map(response -> {
                log.info("📤 Charge response: resultCode={} granted={}",
                         response.getResultCode(), response.getGrantedUnits());
                return ResponseEntity.ok(response);
            })
            // Diameter timed out: return HTTP 504
            .onErrorResume(TimeoutException.class, ex -> {
                log.warn("⏱️ Diameter timeout for msisdn={}", maskMsisdn(request.getMsisdn()));
                return Mono.just(ResponseEntity
                    .status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(ChargeResponse.timeout()));
            })
            // Diameter server unavailable: return HTTP 503
            .onErrorResume(DiameterException.class, ex -> {
                log.warn("⚠️ Diameter unavailable: {}", ex.getMessage());
                return Mono.just(ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ChargeResponse.unavailable()));
            })
            // Any other error: return HTTP 500
            .onErrorResume(Exception.class, ex -> {
                log.error("❌ Unexpected error: {}", ex.getMessage(), ex);
                ChargeResponse error = new ChargeResponse();
                error.setResultCode(500);
                error.setResultMessage("INTERNAL_ERROR");
                error.setError(ex.getMessage());
                return Mono.just(ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(error));
            });
    }

    /**
     * GET /api/v1/health/diameter
     * Quick health check for the Diameter connection status.
     */
    @GetMapping("/health/diameter")
    public Mono<ResponseEntity<String>> diameterHealth() {
        return chargeService.isDiameterReady()
            ? Mono.just(ResponseEntity.ok("{\"status\":\"UP\",\"diameter\":\"CONNECTED\"}"))
            : Mono.just(ResponseEntity.status(503)
                .body("{\"status\":\"DOWN\",\"diameter\":\"DISCONNECTED\"}"));
    }

    private String maskMsisdn(String msisdn) {
        if (msisdn == null || msisdn.length() < 8) return "****";
        return msisdn.substring(0, 4) + "***" + msisdn.substring(msisdn.length() - 4);
    }
}
