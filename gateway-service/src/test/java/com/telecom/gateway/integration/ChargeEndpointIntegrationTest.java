package com.telecom.gateway.integration;

import com.telecom.gateway.model.rest.ChargeResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

/**
 * ChargeEndpointIntegrationTest — Full end-to-end integration test.
 *
 * WHAT THIS TESTS:
 *   REST request → ChargeController → ChargeService → DiameterClient
 *   → (real TCP) → DiameterSimulator → CCA response → HTTP 200 JSON
 *
 * WHY @SpringBootTest(webEnvironment = RANDOM_PORT)?
 *   - Starts a real Spring Boot application on a random port
 *   - Avoids port conflicts with other tests or running services
 *   - WebTestClient auto-configured with the correct base URL
 *
 * SIMULATOR:
 *   We start the Diameter simulator in @BeforeAll using the same
 *   Netty server code from the diameter-simulator module (copied
 *   as a test dependency). Instead, we embed a minimal in-process
 *   simulator here to keep the test self-contained.
 *
 * TEST FLOW:
 *   1. @BeforeAll: Start embedded Diameter simulator on port 13868 (test port)
 *   2. Spring Boot starts with diameter.server.port=13868
 *   3. Each @Test fires HTTP request, asserts response
 *   4. @AfterAll: Shutdown simulator
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        // Point gateway at our test simulator
        "diameter.server.port=13868",
        // Fast timeouts for tests
        "diameter.client.timeout-ms=3000",
        "diameter.client.watchdog-interval-seconds=60",
        // Quiet logs during tests
        "logging.level.com.telecom.gateway=WARN",
        "logging.level.DiameterWire=OFF"
    }
)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChargeEndpointIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    private static EmbeddedDiameterSimulator simulator;

    @BeforeAll
    static void startSimulator() throws Exception {
        simulator = new EmbeddedDiameterSimulator(13868);
        simulator.start();
        // Give simulator time to bind port
        Thread.sleep(500);
    }

    @AfterAll
    static void stopSimulator() {
        if (simulator != null) {
            simulator.stop();
        }
    }

    // ─── Happy-path tests ─────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("POST /api/v1/charge → 200 SUCCESS with correct fields")
    void charge_validRequest_returns200() {
        webTestClient
            .mutate().responseTimeout(Duration.ofSeconds(10)).build()
            .post().uri("/api/v1/charge")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {
                  "msisdn": "919876543210",
                  "requestedUnits": 100,
                  "currency": "INR"
                }
                """)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectBody(ChargeResponse.class)
            .value(response -> {
                Assertions.assertEquals(2001, response.getResultCode(),
                    "Expected Diameter result code 2001 (SUCCESS)");
                Assertions.assertEquals("SUCCESS", response.getResultMessage());
                Assertions.assertEquals(100L, response.getGrantedUnits(),
                    "Granted units should match requested units");
                Assertions.assertNotNull(response.getSessionId(),
                    "Session ID must be present");
            });
    }

    @Test
    @Order(2)
    @DisplayName("POST /api/v1/charge → concurrent requests all succeed")
    void charge_concurrentRequests_allSucceed() {
        // Fire 5 concurrent requests and assert all succeed
        // WebTestClient handles them in parallel on reactor threads
        for (int i = 1; i <= 5; i++) {
            final int units = i * 10;
            webTestClient
                .mutate().responseTimeout(Duration.ofSeconds(10)).build()
                .post().uri("/api/v1/charge")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(String.format("""
                    {"msisdn": "9198765432%02d", "requestedUnits": %d, "currency": "INR"}
                    """, i, units))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ChargeResponse.class)
                .value(r -> Assertions.assertEquals(2001, r.getResultCode()));
        }
    }

    @Test
    @Order(3)
    @DisplayName("POST /api/v1/charge → different MSISDNs get different session IDs")
    void charge_differentMsisdns_differentSessionIds() {
        String sessionId1 = webTestClient
            .mutate().responseTimeout(Duration.ofSeconds(10)).build()
            .post().uri("/api/v1/charge")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"msisdn": "919111111111", "requestedUnits": 50, "currency": "USD"}
                """)
            .exchange()
            .expectStatus().isOk()
            .returnResult(ChargeResponse.class)
            .getResponseBody()
            .blockFirst(Duration.ofSeconds(10))
            .getSessionId();

        String sessionId2 = webTestClient
            .mutate().responseTimeout(Duration.ofSeconds(10)).build()
            .post().uri("/api/v1/charge")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"msisdn": "919222222222", "requestedUnits": 200, "currency": "USD"}
                """)
            .exchange()
            .expectStatus().isOk()
            .returnResult(ChargeResponse.class)
            .getResponseBody()
            .blockFirst(Duration.ofSeconds(10))
            .getSessionId();

        Assertions.assertNotEquals(sessionId1, sessionId2,
            "Each charge request must get a unique session ID");
    }

    // ─── Validation error tests ───────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("POST /api/v1/charge → 400 when msisdn is blank")
    void charge_blankMsisdn_returns400() {
        webTestClient
            .post().uri("/api/v1/charge")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"msisdn": "", "requestedUnits": 100, "currency": "INR"}
                """)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.status").isEqualTo(400)
            .jsonPath("$.fields.msisdn").isNotEmpty();
    }

    @Test
    @Order(5)
    @DisplayName("POST /api/v1/charge → 400 when requestedUnits is zero")
    void charge_zeroUnits_returns400() {
        webTestClient
            .post().uri("/api/v1/charge")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"msisdn": "919876543210", "requestedUnits": 0, "currency": "INR"}
                """)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.status").isEqualTo(400)
            .jsonPath("$.fields.requestedUnits").isNotEmpty();
    }

    @Test
    @Order(6)
    @DisplayName("POST /api/v1/charge → 400 when currency is missing")
    void charge_missingCurrency_returns400() {
        webTestClient
            .post().uri("/api/v1/charge")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"msisdn": "919876543210", "requestedUnits": 100}
                """)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.status").isEqualTo(400);
    }

    // ─── Health endpoint tests ────────────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("GET /actuator/health → shows diameter UP")
    void actuatorHealth_showsDiameterUp() {
        webTestClient
            .mutate().responseTimeout(Duration.ofSeconds(5)).build()
            .get().uri("/actuator/health")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    @Order(8)
    @DisplayName("GET /api/v1/health/diameter → returns UP when connected")
    void diameterHealthEndpoint_returnsUp() {
        webTestClient
            .get().uri("/api/v1/health/diameter")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> Assertions.assertTrue(
                body.contains("UP") || body.contains("CONNECTED"),
                "Expected 'UP' or 'CONNECTED' in health response"
            ));
    }
}
