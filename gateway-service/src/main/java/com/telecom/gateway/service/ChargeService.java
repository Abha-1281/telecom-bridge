package com.telecom.gateway.service;

import com.telecom.gateway.diameter.client.DiameterClient;
import com.telecom.gateway.diameter.codec.DiameterCodec;
import com.telecom.gateway.model.diameter.AvpCodes;
import com.telecom.gateway.model.diameter.DiameterMessage;
import com.telecom.gateway.model.diameter.DiameterMessage.Avp;
import com.telecom.gateway.model.rest.ChargeRequest;
import com.telecom.gateway.model.rest.ChargeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Optional;

/**
 * ChargeService — Orchestrates the charging flow.
 *
 * RESPONSIBILITIES:
 * 1. Receives ChargeRequest (from REST layer)
 * 2. Calls DiameterClient.sendCcr() (non-blocking, returns CompletableFuture)
 * 3. Converts CompletableFuture → Mono (Spring WebFlux reactive type)
 * 4. Parses CCA response → ChargeResponse
 * 5. Returns ChargeResponse to controller
 *
 * WHY CompletableFuture → Mono conversion?
 * DiameterClient uses Java standard CompletableFuture.
 * Spring WebFlux uses Project Reactor's Mono/Flux.
 * Mono.fromCompletionStage() bridges them — the Mono completes when
 * the CompletableFuture completes, on whatever thread completes it.
 */
@Service
public class ChargeService {

    private static final Logger log = LoggerFactory.getLogger(ChargeService.class);

    private final DiameterClient diameterClient;
    private final DiameterCodec codec;

    public ChargeService(DiameterClient diameterClient) {
        this.diameterClient = diameterClient;
        this.codec = new DiameterCodec();
    }

    /**
     * Processes a charge request end-to-end.
     *
     * FLOW:
     * 1. Call diameterClient.sendCcr() → returns CompletableFuture<DiameterMessage>
     * 2. Wrap in Mono.fromCompletionStage() → Mono<DiameterMessage>
     * 3. Map DiameterMessage (CCA) → ChargeResponse (JSON model)
     * 4. Return Mono<ChargeResponse> to controller
     *
     * The entire chain is non-blocking. The HTTP thread is never blocked.
     *
     * @param request Validated REST request
     * @return Mono that completes with ChargeResponse when CCA arrives
     */
    public Mono<ChargeResponse> processCharge(ChargeRequest request) {

        // Step 1+2: Send CCR and wrap future as Mono
        // Mono.fromCompletionStage: creates a Mono that:
        //   - Subscribes to nothing immediately
        //   - Emits when CompletableFuture completes
        //   - Errors when CompletableFuture completes exceptionally
        return Mono.fromCompletionStage(
                    () -> diameterClient.sendCcr(
                        request.getMsisdn(),
                        request.getRequestedUnits(),
                        request.getCurrency()
                    )
                )
                // publishOn: ensures CCA processing happens on a bounded elastic thread
                // (not on the Netty I/O thread which completed the future)
                .publishOn(Schedulers.boundedElastic())

                // Step 3: Parse CCA → ChargeResponse
                .map(this::parseCca)

                // Add request context to any error for better logging
                .doOnError(ex -> log.debug("Charge failed for msisdn={}: {}",
                                           maskMsisdn(request.getMsisdn()),
                                           ex.getClass().getSimpleName()));
    }

    /**
     * Parses a CCA (Credit Control Answer) DiameterMessage into a ChargeResponse.
     *
     * Extracts:
     * - Session-Id → sessionId
     * - Result-Code → resultCode + resultMessage
     * - Granted-Service-Unit → grantedUnits
     */
    private ChargeResponse parseCca(DiameterMessage cca) {
        // Extract Result-Code (mandatory in CCA)
        long resultCode = cca.findAvp(AvpCodes.RESULT_CODE)
                             .map(codec::readUint32)
                             .orElse(0L);

        // Extract Session-Id
        String sessionId = cca.findAvp(AvpCodes.SESSION_ID)
                              .map(codec::readUtf8)
                              .orElse("UNKNOWN");

        // Extract Granted-Service-Unit (grouped AVP)
        long grantedUnits = extractGrantedUnits(cca);

        log.debug("CCA parsed: sessionId={} resultCode={} grantedUnits={}",
                  sessionId, resultCode, grantedUnits);

        // Map Diameter result code → ChargeResponse
        if (resultCode == ChargeResponse.DiameterResultCode.SUCCESS) {
            return ChargeResponse.success(sessionId, grantedUnits, grantedUnits);
        } else if (resultCode == ChargeResponse.DiameterResultCode.CREDIT_LIMIT_REACHED) {
            return ChargeResponse.insufficientCredit(sessionId);
        } else {
            return ChargeResponse.fromDiameterResult(sessionId, (int) resultCode, grantedUnits);
        }
    }

    /**
     * Extracts granted units from the Granted-Service-Unit grouped AVP.
     *
     * Granted-Service-Unit is a Grouped AVP that may contain:
     *   - CC-Service-Specific-Units (generic units)
     *   - CC-Total-Octets (data bytes)
     *   - CC-Time (seconds)
     *
     * We check in priority order: Specific → Octets → Time
     */
    private long extractGrantedUnits(DiameterMessage cca) {
        Optional<Avp> gsuAvp = cca.findAvp(AvpCodes.GRANTED_SERVICE_UNIT);
        if (gsuAvp.isEmpty()) {
            return 0L;
        }

        try {
            DiameterMessage gsuChildren = codec.readGrouped(gsuAvp.get());

            // Try CC-Service-Specific-Units first
            Optional<Avp> specificUnits = gsuChildren.findAvp(AvpCodes.CC_SERVICE_SPECIFIC_UNITS);
            if (specificUnits.isPresent()) {
                return codec.readUint64(specificUnits.get());
            }

            // Fallback to CC-Total-Octets (data bytes)
            Optional<Avp> totalOctets = gsuChildren.findAvp(AvpCodes.CC_TOTAL_OCTETS);
            if (totalOctets.isPresent()) {
                return codec.readUint64(totalOctets.get());
            }

            // Fallback to CC-Time (seconds)
            Optional<Avp> ccTime = gsuChildren.findAvp(AvpCodes.CC_TIME);
            if (ccTime.isPresent()) {
                return codec.readUint32(ccTime.get());
            }

        } catch (Exception e) {
            log.warn("Failed to parse Granted-Service-Unit: {}", e.getMessage());
        }

        return 0L;
    }

    public boolean isDiameterReady() {
        return diameterClient.isReady();
    }

    private String maskMsisdn(String msisdn) {
        if (msisdn == null || msisdn.length() < 8) return "****";
        return msisdn.substring(0, 4) + "***" + msisdn.substring(msisdn.length() - 4);
    }
}
