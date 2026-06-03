# Telecom-Bridge — REST-to-Diameter Gateway

A high-performance microservice that translates JSON REST calls into binary Diameter Credit Control (CCR/CCA) protocol messages per **RFC 6733 / RFC 4006**, targeting **100 TPS with p95 latency < 100ms**.

```
REST Client → Spring Boot WebFlux → Netty Diameter Client → OCS Simulator
                                         ↕  TCP :3868
                                   ConcurrentHashMap<HopByHop, CompletableFuture>
```

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    TELECOM-BRIDGE GATEWAY                       │
│                                                                 │
│  POST /api/v1/charge                                            │
│       │                                                         │
│  [ChargeController] ← Spring WebFlux (non-blocking HTTP)        │
│       │                                                         │
│  [ChargeService]                                                │
│       │                                                         │
│  [DiameterClient] ←→ ConcurrentHashMap<hopByHopId, Future>      │
│       │                                                         │
│  [Netty Channel] ──TCP:3868──► [Diameter OCS Simulator]         │
│                    CCR/CCA                                      │
└─────────────────────────────────────────────────────────────────┘
```

### Key Design Decisions

| Decision | Choice | Why |
|---|---|---|
| HTTP framework | Spring Boot 3 WebFlux | Non-blocking I/O — a single thread handles many concurrent requests |
| Diameter transport | Raw Netty (no library) | Full control over AVP encoding, zero extra dependencies |
| Async matching | `ConcurrentHashMap<Long, CompletableFuture>` | Maps Hop-by-Hop ID to pending request — O(1) lookup |
| Connection mgmt | Persistent TCP + DWR/DWA watchdog | Avoids handshake cost per request |
| Error handling | Timeout → 504, Disconnected → 503 | Standard HTTP semantics for gateway errors |

---

## Project Structure

```
telecom-bridge/
├── gateway-service/          ← Spring Boot WebFlux REST gateway
│   └── src/main/java/com/telecom/gateway/
│       ├── controller/       ChargeController.java
│       ├── service/          ChargeService.java
│       ├── diameter/
│       │   ├── client/       DiameterClient.java  ← CORE
│       │   ├── codec/        DiameterCodec.java   ← AVP encode/decode
│       │   └── handler/      Netty pipeline handlers
│       ├── model/
│       │   ├── diameter/     DiameterMessage, DiameterHeader, AvpCodes
│       │   └── rest/         ChargeRequest, ChargeResponse
│       ├── exception/        GlobalExceptionHandler.java
│       └── health/           DiameterHealthIndicator.java
│
├── diameter-simulator/       ← Standalone OCS simulator (port 3868)
│   └── src/main/java/com/telecom/simulator/
│
├── load-test/                ← Gatling load test (Scala DSL)
│   └── src/test/scala/com/telecom/loadtest/ChargeSimulation.scala
│
├── Dockerfile.gateway        ← Multi-stage Docker build for gateway
├── Dockerfile.simulator      ← Multi-stage Docker build for simulator
└── docker-compose.yml        ← Wires both services together
```

---

## Prerequisites

| Tool | Version | Purpose |
|---|---|---|
| Java JDK | 17+ | Build and run |
| Maven | 3.9+ | Build tool |
| Docker + Docker Compose | 24+ | Container deployment |
| Wireshark / tcpdump | any | PCAP capture (optional) |

---

## Quick Start

### Option A — Run locally (no Docker)

**1. Build everything:**
```bash
cd telecom-bridge
mvn clean package -DskipTests
```

**2. Start the Diameter simulator:**
```bash
java -jar diameter-simulator/target/diameter-simulator.jar
# Output: ✅ Diameter OCS Simulator started on port 3868
```

**3. Start the gateway (new terminal):**
```bash
java -jar gateway-service/target/gateway-service-*.jar
# Output: ✅ CEA received: resultCode=2001 — Diameter session ready
```

**4. Test a charge request:**
```bash
curl -s -X POST http://localhost:8080/api/v1/charge \
  -H "Content-Type: application/json" \
  -d '{"msisdn":"919876543210","requestedUnits":100,"currency":"INR"}' | jq
```

Expected response:
```json
{
  "resultCode": 2001,
  "resultMessage": "SUCCESS",
  "grantedUnits": 100,
  "sessionId": "gw-1234567890-abc123"
}
```

---

### Option B — Run with Docker Compose

```bash
# Build images and start both services
docker compose up --build

# In another terminal, test:
curl -s -X POST http://localhost:8080/api/v1/charge \
  -H "Content-Type: application/json" \
  -d '{"msisdn":"919876543210","requestedUnits":50,"currency":"USD"}' | jq
```

Watch logs in real-time:
```bash
docker compose logs -f gateway
docker compose logs -f diameter-simulator
```

Stop everything:
```bash
docker compose down
```

---

## API Reference

### `POST /api/v1/charge`

**Request:**
```json
{
  "msisdn": "919876543210",
  "requestedUnits": 100,
  "currency": "INR"
}
```

| Field | Type | Validation |
|---|---|---|
| `msisdn` | string | 7–15 digits, E.164 format (no `+`) |
| `requestedUnits` | integer | 1–1,000,000 |
| `currency` | string | 3-letter ISO 4217 code (e.g., `INR`, `USD`) |

**Success Response (200):**
```json
{
  "resultCode": 2001,
  "resultMessage": "SUCCESS",
  "grantedUnits": 100,
  "sessionId": "gw-1717000000000-abc12345"
}
```

**Error Responses:**

| HTTP Code | Scenario |
|---|---|
| `400 Bad Request` | Validation failure (invalid msisdn, units=0, etc.) |
| `503 Service Unavailable` | Diameter server is down |
| `504 Gateway Timeout` | Diameter server did not respond within 5 seconds |

**Validation Error Example:**
```json
{
  "status": 400,
  "error": "Validation Failed",
  "timestamp": "2024-01-01T12:00:00",
  "errors": {
    "msisdn": "msisdn must be 7-15 digits (international format, no +)"
  }
}
```

### `GET /actuator/health`

Spring Actuator health endpoint — shows Diameter connection status:
```json
{
  "status": "UP",
  "components": {
    "diameter": {
      "status": "UP",
      "details": {
        "connection": "CONNECTED",
        "server": "localhost:3868"
      }
    }
  }
}
```

---

## Running Tests

### Unit tests (AVP codec — 25 tests):
```bash
mvn test -pl gateway-service -Dtest=DiameterCodecTest
```

### Integration tests (full REST-to-Diameter flow — 8 tests):
```bash
mvn test -pl gateway-service -Dtest=ChargeEndpointIntegrationTest
```
> The integration tests use `EmbeddedDiameterSimulator` — no external process needed.

### All tests:
```bash
mvn test -pl gateway-service
# Expected: Tests run: 33, Failures: 0, Errors: 0
```

---

## Load Testing (Gatling)

### Prerequisites
- Gateway must be running (local or Docker)

### Run quick smoke test (10 TPS, 100 requests):
```bash
mvn gatling:test -pl load-test \
  -Dgatling.targetTps=10 \
  -Dgatling.totalTransactions=100
```

### Run full load test (100 TPS, 500K transactions):
```bash
mvn gatling:test -pl load-test
```
> This runs for ~90 minutes. Gatling generates an HTML report at:  
> `load-test/target/gatling/chargesimulation-*/index.html`

### Against Docker deployment:
```bash
docker compose up -d  # start services first
mvn gatling:test -pl load-test -Dgatling.baseUrl=http://localhost:8080
```

### Success Criteria:
| Metric | Threshold |
|---|---|
| Error rate | 0% |
| p95 latency | ≤ 100ms |
| p99 latency | ≤ 200ms |
| Mean latency | ≤ 50ms |

---

## PCAP Capture

Capture a live Diameter protocol trace during testing:

```bash
# In one terminal — start capture (macOS: en0 or lo0, Linux: eth0 or lo)
sudo tcpdump -i lo0 -w transaction_flow.pcap port 3868 or port 8080

# In another terminal — run 10 charge requests
for i in $(seq 1 10); do
  curl -s -X POST http://localhost:8080/api/v1/charge \
    -H "Content-Type: application/json" \
    -d "{\"msisdn\":\"9198765432${i}\",\"requestedUnits\":100,\"currency\":\"INR\"}" &
done; wait

# Stop tcpdump with Ctrl+C
# Open transaction_flow.pcap in Wireshark, filter: diameter
```

---

## Diameter Protocol Implementation Details

### Message Flow (per RFC 6733 / RFC 4006)

```
Gateway                           OCS Simulator
   │──── CER (Cmd:257, App:0) ────►│   TCP connect + capabilities exchange
   │◄─── CEA (Result: 2001) ────────│
   │                                │
   │──── CCR (Cmd:272, App:4) ────►│   Credit Control Request
   │◄─── CCA (Result: 2001) ────────│   Credit Control Answer
   │                                │
   │──── DWR (Cmd:280, App:0) ────►│   Device Watchdog (every 30s)
   │◄─── DWA (Result: 2001) ────────│
```

### Key AVPs Implemented (RFC 4006)

| AVP Code | Name | Type |
|---|---|---|
| 258 | Auth-Application-Id | Unsigned32 |
| 263 | Session-Id | UTF8String |
| 264 | Origin-Host | DiameterIdentity |
| 296 | Origin-Realm | DiameterIdentity |
| 268 | Result-Code | Unsigned32 |
| 416 | CC-Request-Type | Enumerated |
| 415 | CC-Request-Number | Unsigned32 |
| 437 | Requested-Service-Unit | Grouped |
| 431 | Granted-Service-Unit | Grouped |
| 446 | CC-Service-Specific-Units | Unsigned64 |
| 443 | Subscription-Id | Grouped |
| 444 | Subscription-Id-Data | UTF8String |
| 450 | Subscription-Id-Type | Enumerated |

### AVP Encoding (RFC 6733 §4.1)

Every AVP is padded to a 4-byte boundary. The `AVP-Length` field contains the **unpadded** length; padding bytes (zeros) are NOT included in the length.

```
┌─────────────────────────────────┐
│  AVP Code           (4 bytes)   │
│  Flags + Length     (4 bytes)   │
│  [Vendor-Id]        (4 bytes?)  │
│  Value              (N bytes)   │
│  Padding            (0-3 bytes) │
└─────────────────────────────────┘
paddedLen = (avpLen + 3) & ~3
```

---

## Configuration Reference

`application.yml` key settings:

```yaml
diameter:
  server:
    host: ${DIAMETER_HOST:localhost}   # env var override for Docker
    port: ${DIAMETER_PORT:3868}
  client:
    timeout-ms: ${DIAMETER_TIMEOUT_MS:5000}
    watchdog-interval-seconds: 30
```

Environment variables for Docker:

| Variable | Default | Description |
|---|---|---|
| `DIAMETER_HOST` | `localhost` | Diameter server hostname |
| `DIAMETER_PORT` | `3868` | Diameter server port |
| `DIAMETER_TIMEOUT_MS` | `5000` | Request timeout in milliseconds |
| `SPRING_PROFILES_ACTIVE` | `default` | Spring profile |

---

## Troubleshooting

**Gateway can't connect to simulator:**
```bash
# Check if simulator is running
nc -zv localhost 3868
# Expected: Connection to localhost 3868 port [tcp] succeeded!
```

**Seeing 503 Service Unavailable:**
- Diameter simulator is not running or has crashed
- Gateway will auto-reconnect every 5 seconds — check logs for `🔄 Scheduling reconnection`

**Seeing 504 Gateway Timeout:**
- Simulator is connected but not responding within 5 seconds
- Check simulator logs for errors

**Wireshark shows malformed packets:**
- Apply filter: `diameter`
- Check that AVP lengths are multiples of 4 (padding issue)

---

## Key Technical Learnings

1. **`ctx.writeAndFlush()` vs `ctx.channel().writeAndFlush()`** — In Netty, `ctx.write` goes toward HEAD, skipping handlers added AFTER the current one. Always use `ctx.channel().writeAndFlush()` from business handlers to ensure encoders run.

2. **CER timing** — Send CER in `channelActive()`, NOT in the connect `ChannelFuture` listener. The listener fires before `OP_READ` is registered; the CEA will arrive but Netty won't see it.

3. **AVP Padding** — The most common Diameter bug. The `AVP-Length` field is the UNPADDED size; wire size is rounded up to 4 bytes.

4. **Unsigned integers** — Diameter Unsigned32 must be stored as Java `long` to avoid sign issues. Read with `& 0xFFFFFFFFL`.

