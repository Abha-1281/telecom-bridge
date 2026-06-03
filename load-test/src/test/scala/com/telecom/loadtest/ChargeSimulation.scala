package com.telecom.loadtest

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.util.Random

/**
 * ChargeSimulation — Gatling load test for the Telecom-Bridge REST gateway.
 *
 * TARGET: 100 TPS for 500,000 total transactions
 * SUCCESS CRITERIA: p95 latency < 100ms, zero errors
 *
 * HOW GATLING WORKS:
 *   Gatling uses a non-blocking NIO HTTP client (Netty under the hood).
 *   "Virtual users" in Gatling are NOT OS threads — they're coroutines.
 *   This means we can simulate thousands of concurrent users with a small
 *   thread pool, exactly matching how our gateway handles requests.
 *
 * LOAD PROFILE:
 *   - Ramp up to 100 TPS over 30 seconds (gentle warmup)
 *   - Hold at 100 TPS for the remaining duration (~5000 seconds for 500K)
 *   - The "constantUsersPerSec" injection means Gatling opens new virtual
 *     users at 100/second. Each user sends 1 request and finishes.
 *     Net effect: 100 requests/second hitting the server.
 *
 * RUN:
 *   mvn gatling:test -pl load-test                         # default: localhost:8080
 *   mvn gatling:test -pl load-test -Dgatling.baseUrl=http://your-host:8080
 *
 * QUICK SMOKE TEST (10 TPS for 100 requests):
 *   mvn gatling:test -pl load-test -Dgatling.targetTps=10 -Dgatling.totalTransactions=100
 */
class ChargeSimulation extends Simulation {

  // ─── Configuration ────────────────────────────────────────────────────────
  val baseUrl: String   = System.getProperty("gatling.baseUrl", "http://localhost:8080")
  val targetTps: Int    = System.getProperty("gatling.targetTps", "100").toInt
  val totalTx: Int      = System.getProperty("gatling.totalTransactions", "500000").toInt

  // Duration needed to reach totalTx at targetTps
  // Add 30s ramp-up on top so the ramp doesn't eat into the steady-state count
  val rampSeconds: Int   = 30
  val steadySeconds: Int = (totalTx / targetTps) + rampSeconds

  println(s"""
    |╔══════════════════════════════════════════════════╗
    |║  Telecom-Bridge Load Test                        ║
    |║  Target:  $baseUrl
    |║  TPS:     $targetTps requests/second             ║
    |║  Total:   $totalTx transactions                  ║
    |║  Duration: ~${steadySeconds}s (${steadySeconds/60}m)
    |╚══════════════════════════════════════════════════╝
  """.stripMargin)

  // ─── HTTP Protocol ────────────────────────────────────────────────────────
  val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    // Gatling connection pool — keep connections alive (like a real client)
    .shareConnections

  // ─── Request Feeder — generates random MSISDN + units per request ─────────
  // Feeder provides test data; circular means it loops forever
  val chargeFeeder = Iterator.continually {
    val msisdn = s"91${9000000000L + Random.nextInt(999999999)}"
    val units  = 1 + Random.nextInt(999)
    val currencies = Array("INR", "USD", "EUR", "GBP", "SGD")
    val currency = currencies(Random.nextInt(currencies.length))
    Map("msisdn" -> msisdn, "units" -> units, "currency" -> currency)
  }

  // ─── Scenario ─────────────────────────────────────────────────────────────
  val chargeScenario = scenario("Credit Control Charge")
    .feed(chargeFeeder)
    .exec(
      http("POST /api/v1/charge")
        .post("/api/v1/charge")
        .body(StringBody(
          """{"msisdn":"${msisdn}","requestedUnits":${units},"currency":"${currency}"}"""
        ))
        .check(status.is(200))                          // Must be HTTP 200
        .check(jsonPath("$.resultCode").is("2001"))     // Must be Diameter SUCCESS
        .check(jsonPath("$.grantedUnits").exists)       // Must have granted units
        // Latency SLA is enforced at the global assertions level (p95/p99), NOT per-request.
        // Per-request time checks mark requests as KO, inflating the error rate metric.
    )

  // ─── Load Injection Profile ───────────────────────────────────────────────
  // Phase 1: Ramp from 0 → targetTps over 30 seconds (warmup the JVM)
  // Phase 2: Hold steady at targetTps for the rest of the test
  setUp(
    chargeScenario.inject(
      rampUsersPerSec(1).to(targetTps).during(rampSeconds.seconds),
      constantUsersPerSec(targetTps).during(steadySeconds.seconds)
    )
  )
  .protocols(httpProtocol)
  // ─── Assertions — test FAILS if these thresholds are breached ─────────────
  .assertions(
    // All requests must succeed (0% errors)
    global.failedRequests.percent.is(0),
    // p95 latency ≤ 100ms — exact requirement from the task specification
    global.responseTime.percentile(95).lte(100),
    // p99 latency ≤ 150ms
    global.responseTime.percentile(99).lte(150),
    // Mean latency ≤ 50ms (simulator 5-20ms delay + gateway ~10ms overhead)
    global.responseTime.mean.lte(50)
  )
}
