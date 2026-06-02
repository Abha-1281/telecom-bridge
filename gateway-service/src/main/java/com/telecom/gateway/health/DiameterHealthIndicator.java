package com.telecom.gateway.health;

import com.telecom.gateway.diameter.client.DiameterClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * DiameterHealthIndicator — Exposes Diameter connection status via Spring Actuator.
 *
 * Accessible at: GET /actuator/health
 *
 * Response when UP:
 * {
 *   "status": "UP",
 *   "components": {
 *     "diameter": {
 *       "status": "UP",
 *       "details": {
 *         "host": "localhost",
 *         "port": 3868,
 *         "connected": true,
 *         "handshakeComplete": true,
 *         "pendingRequests": 0
 *       }
 *     }
 *   }
 * }
 *
 * Response when DOWN (Diameter server unreachable):
 * {
 *   "status": "DOWN",
 *   "components": { "diameter": { "status": "DOWN", "details": {...} } }
 * }
 *
 * WHY ReactiveHealthIndicator?
 * We're in a WebFlux (reactive) context. Using ReactiveHealthIndicator
 * instead of HealthIndicator avoids blocking a thread during health checks.
 * For Diameter we just read AtomicBoolean flags — no I/O needed — so this
 * is fast regardless.
 */
@Component("diameter")
public class DiameterHealthIndicator implements ReactiveHealthIndicator {

    private final DiameterClient diameterClient;

    public DiameterHealthIndicator(DiameterClient diameterClient) {
        this.diameterClient = diameterClient;
    }

    @Override
    public Mono<Health> health() {
        boolean connected = diameterClient.isConnected();
        boolean ready = diameterClient.isReady();
        int pending = diameterClient.getPendingRequestCount();

        Health health;
        if (ready) {
            health = Health.up()
                .withDetail("state", "CONNECTED_AND_READY")
                .withDetail("connected", true)
                .withDetail("handshakeComplete", true)
                .withDetail("pendingRequests", pending)
                .build();
        } else if (connected) {
            // TCP is up but CER/CEA hasn't completed yet (or was rejected)
            health = Health.down()
                .withDetail("state", "CONNECTED_HANDSHAKE_PENDING")
                .withDetail("connected", true)
                .withDetail("handshakeComplete", false)
                .withDetail("pendingRequests", pending)
                .build();
        } else {
            health = Health.down()
                .withDetail("state", "DISCONNECTED")
                .withDetail("connected", false)
                .withDetail("handshakeComplete", false)
                .withDetail("pendingRequests", 0)
                .build();
        }

        return Mono.just(health);
    }
}
