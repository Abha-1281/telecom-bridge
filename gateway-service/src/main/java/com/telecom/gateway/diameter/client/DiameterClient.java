package com.telecom.gateway.diameter.client;

import com.telecom.gateway.diameter.codec.DiameterCodec;
import com.telecom.gateway.diameter.handler.DiameterClientInitializer;
import com.telecom.gateway.model.diameter.AvpCodes;
import com.telecom.gateway.model.diameter.DiameterHeader;
import com.telecom.gateway.model.diameter.DiameterMessage;
import com.telecom.gateway.model.diameter.DiameterMessage.Avp;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DiameterClient — The async Diameter credit control client.
 *
 * THIS IS THE CORE OF THE PROJECT.
 *
 * WHAT IT DOES:
 * 1. Maintains a persistent TCP connection to the Diameter server
 * 2. Performs CER/CEA handshake on startup
 * 3. Sends DWR every 30 seconds to keep the connection alive
 * 4. Sends CCR messages and returns CompletableFuture<DiameterMessage>
 * 5. Matches incoming CCA responses to pending CCR requests
 *
 * THE ASYNC MATCHING MECHANISM (KEY CONCEPT):
 *
 * Problem: We send CCR1, CCR2, CCR3 simultaneously.
 * Answers CCA1, CCA2, CCA3 come back in any order.
 * How do we know which CCA belongs to which CCR?
 *
 * Solution: Hop-by-Hop ID
 * - Each CCR gets a unique Hop-by-Hop ID (like a ticket number)
 * - The CCA MUST carry the same Hop-by-Hop ID
 * - We store: pendingRequests[hopByHopId] = CompletableFuture
 * - When CCA arrives: pendingRequests.remove(cca.hopByHopId).complete(cca)
 *
 * Visual:
 *
 *   REST Thread          Netty I/O Thread
 *       │                      │
 *   sendCCR(req)               │
 *       │  hop=42              │
 *   map[42]=future             │
 *       │──────CCR(hop=42)────▶│──── TCP ────▶ Simulator
 *       │                      │
 *   return future              │  ◀── TCP ──── CCA(hop=42)
 *       │ (REST thread FREE)   │
 *       │              map[42].complete(cca)
 *       │◀─────────────────────│
 *   future.get() unblocks      │
 *   HTTP response sent         │
 *
 * THREAD SAFETY:
 * - ConcurrentHashMap: thread-safe without locking (for most operations)
 * - AtomicLong: lock-free counter for Hop-by-Hop ID generation
 * - CompletableFuture: designed for multi-thread handoff
 */
@Component
public class DiameterClient {

    private static final Logger log = LoggerFactory.getLogger(DiameterClient.class);

    // ─── Configuration (from application.yml) ─────────────────────────
    @Value("${diameter.server.host:localhost}")
    private String serverHost;

    @Value("${diameter.server.port:3868}")
    private int serverPort;

    @Value("${diameter.client.origin-host:gateway.telecom.com}")
    private String originHost;

    @Value("${diameter.client.origin-realm:telecom.com}")
    private String originRealm;

    @Value("${diameter.client.timeout-ms:5000}")
    private long timeoutMs;

    @Value("${diameter.client.watchdog-interval-seconds:30}")
    private int watchdogIntervalSeconds;

    // ─── THE CORE DATA STRUCTURE ────────────────────────────────────────
    /**
     * Maps Hop-by-Hop ID → pending CompletableFuture<CCA>
     *
     * WHY ConcurrentHashMap and not HashMap?
     * - Multiple REST threads call sendCCR() simultaneously (writing)
     * - Netty I/O thread calls onCcaReceived() simultaneously (removing)
     * - ConcurrentHashMap handles concurrent reads/writes without locking
     * - Regular HashMap would cause ConcurrentModificationException or corruption
     *
     * Memory concern: what if CCA never arrives? Future stays in map forever!
     * Solution: orTimeout() automatically removes entries after timeout.
     */
    private final ConcurrentHashMap<Long, CompletableFuture<DiameterMessage>>
        pendingRequests = new ConcurrentHashMap<>();

    // ─── Hop-by-Hop ID generator ─────────────────────────────────────
    /**
     * Each CCR needs a UNIQUE Hop-by-Hop ID.
     * AtomicLong.incrementAndGet() is thread-safe, lock-free (uses CAS internally).
     * We use the low 32 bits only (Diameter HopByHop is 32-bit unsigned).
     */
    private final AtomicLong hopByHopCounter = new AtomicLong(
        System.currentTimeMillis() & 0xFFFFFFFFL // Random-ish starting point
    );

    // ─── Session ID counter ───────────────────────────────────────────
    private final AtomicLong sessionCounter = new AtomicLong(0);

    // ─── Netty components ────────────────────────────────────────────
    private EventLoopGroup eventLoopGroup;
    private Channel channel;           // The TCP connection to Diameter server
    private final DiameterCodec codec = new DiameterCodec();

    // ─── State flags ─────────────────────────────────────────────────
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean cerCompleted = new AtomicBoolean(false);
    private ScheduledExecutorService watchdogScheduler;

    // ═══════════════════════════════════════════════════════════════════
    // LIFECYCLE — Startup and Shutdown
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Called by Spring after bean construction.
     * Starts Diameter connection fully asynchronously — does NOT block.
     *
     * WHY NON-BLOCKING?
     * @PostConstruct runs on Spring's main initialization thread.
     * If we block here waiting for CEA, AND the Netty pipeline needs to
     * call back into a Spring bean that hasn't been initialized yet,
     * we'd have a deadlock. Keeping it async is safer and faster.
     *
     * The CER/CEA handshake completes within milliseconds in the background.
     * sendCcr() checks isReady() and returns 503 if not yet connected.
     */
    /**
     * Called by Spring after bean construction — creates the EventLoopGroup ONCE.
     * The group is reused across reconnect attempts (no thread leaks).
     * The actual TCP connect is delegated to doConnect().
     */
    @PostConstruct
    public void connect() {
        // Create I/O thread pool once for the lifetime of this bean.
        // Reusing the group on reconnects avoids creating new threads every time.
        if (eventLoopGroup == null) {
            eventLoopGroup = new NioEventLoopGroup(2); // 2 I/O threads for Diameter
        }
        doConnect();
    }

    /**
     * Performs the actual TCP connect. Safe to call multiple times (reconnects).
     * Uses the already-created eventLoopGroup — no thread leaks on reconnect.
     *
     * NOTE: CER is NOT sent here. It is sent from onChannelActive(), which fires
     * AFTER Netty registers OP_READ on the NIO selector — guaranteeing the CEA
     * response bytes are always captured.
     */
    private void doConnect() {
        log.info("🔌 Connecting to Diameter server {}:{}...", serverHost, serverPort);

        Bootstrap bootstrap = new Bootstrap()
            .group(eventLoopGroup)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .handler(new DiameterClientInitializer(this, codec));

        bootstrap.connect(serverHost, serverPort).addListener((ChannelFuture f) -> {
            if (f.isSuccess()) {
                log.info("✅ TCP connection established to {}:{}", serverHost, serverPort);
                // CER sent from onChannelActive()
            } else {
                log.error("❌ TCP connect failed: {} — will retry in 5s",
                          f.cause().getMessage());
                scheduleReconnect();
            }
        });
    }

    /**
     * Called by Spring before bean destruction.
     * Gracefully disconnects from Diameter server.
     */
    @PreDestroy
    public void disconnect() {
        log.info("🔌 Disconnecting from Diameter server...");

        if (watchdogScheduler != null) {
            watchdogScheduler.shutdown();
        }

        // Cancel all pending requests
        pendingRequests.forEach((hopByHop, future) -> {
            future.completeExceptionally(
                new DiameterException("Client shutting down", 503)
            );
        });
        pendingRequests.clear();

        if (channel != null && channel.isActive()) {
            sendDpr(); // Graceful Diameter disconnect
            try {
                channel.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
        }

        log.info("✅ Diameter client disconnected");
    }

    // ═══════════════════════════════════════════════════════════════════
    // MAIN PUBLIC API
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Sends a Credit-Control-Request and returns a future for the answer.
     *
     * THIS METHOD NEVER BLOCKS.
     *
     * Returns immediately with a CompletableFuture.
     * The future completes when CCA arrives (typically 50-100ms later).
     * The future fails if no CCA arrives within timeoutMs.
     *
     * @param msisdn         Subscriber's phone number (e.g., "919876543210")
     * @param requestedUnits How many units to request (data, time, SMS, etc.)
     * @param currency       ISO currency code (e.g., "INR")
     * @return CompletableFuture<DiameterMessage> that resolves to CCA
     * @throws DiameterException if not connected to Diameter server
     */
    public CompletableFuture<DiameterMessage> sendCcr(String msisdn,
                                                       long requestedUnits,
                                                       String currency) {
        // Check if we're connected and handshake is complete
        if (!connected.get() || !cerCompleted.get()) {
            CompletableFuture<DiameterMessage> failed = new CompletableFuture<>();
            failed.completeExceptionally(
                new DiameterException("Diameter server not available", 503)
            );
            return failed;
        }

        // Generate unique Hop-by-Hop ID for this request
        // & 0xFFFFFFFFL → keep only 32 bits (Diameter HopByHop is Unsigned32)
        long hopByHop = hopByHopCounter.incrementAndGet() & 0xFFFFFFFFL;
        String sessionId = generateSessionId();

        // Create the CompletableFuture that will hold the CCA response
        CompletableFuture<DiameterMessage> responseFuture = new CompletableFuture<>();

        // ─── TIMEOUT HANDLING ──────────────────────────────────────────
        // If CCA doesn't arrive within timeoutMs:
        //   1. Future completes with TimeoutException
        //   2. pendingRequests entry is removed (prevents memory leak)
        responseFuture.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                      .exceptionally(ex -> {
                          // Remove from map to prevent memory leak
                          pendingRequests.remove(hopByHop);
                          if (ex instanceof TimeoutException) {
                              log.warn("⏱️ CCR timeout: hopByHop=0x{} msisdn={}",
                                       Long.toHexString(hopByHop), maskMsisdn(msisdn));
                          }
                          return null; // caller handles the exception
                      });

        // ─── REGISTER PENDING REQUEST ──────────────────────────────────
        // MUST be done BEFORE sending CCR to avoid a race condition where
        // CCA arrives before we put the future in the map
        pendingRequests.put(hopByHop, responseFuture);

        // ─── BUILD AND SEND CCR ────────────────────────────────────────
        DiameterMessage ccr = buildCcr(sessionId, hopByHop, msisdn, requestedUnits, currency);

        // writeAndFlush is non-blocking — returns immediately
        // Netty queues the bytes and sends them on the I/O thread
        channel.writeAndFlush(ccr).addListener(writeResult -> {
            if (!writeResult.isSuccess()) {
                // Write failed (connection dropped?) — complete future with error
                pendingRequests.remove(hopByHop);
                responseFuture.completeExceptionally(
                    new DiameterException("Failed to send CCR to Diameter server", 503)
                );
                log.error("❌ CCR write failed: hopByHop=0x{} cause={}",
                          Long.toHexString(hopByHop), writeResult.cause().getMessage());
            }
        });

        log.debug("📤 CCR sent: hopByHop=0x{} msisdn={} units={}",
                  Long.toHexString(hopByHop), maskMsisdn(msisdn), requestedUnits);

        return responseFuture;
    }

    // ═══════════════════════════════════════════════════════════════════
    // CALLED BY NETTY HANDLER — Incoming message processing
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Called by DiameterClientHandler when a CCA arrives from server.
     *
     * THIS IS THE MATCH COMPLETION — the core async mechanism.
     *
     * This method runs on the Netty I/O thread.
     * It must be FAST — no heavy processing here.
     * Just find the matching future and complete it.
     */
    public void onMessageReceived(DiameterMessage message) {
        int commandCode = message.getCommandCode();
        long hopByHop = message.getHopByHopId();

        if (commandCode == AvpCodes.CMD_CREDIT_CONTROL && !message.isRequest()) {
            // CCA received — find and complete the matching future
            CompletableFuture<DiameterMessage> future = pendingRequests.remove(hopByHop);

            if (future != null) {
                // Complete the future with the CCA response
                // This unblocks the CompletableFuture chain in ChargeService
                future.complete(message);
                log.debug("📨 CCA matched: hopByHop=0x{} pendingCount={}",
                          Long.toHexString(hopByHop), pendingRequests.size());
            } else {
                // No matching request found — could be:
                // 1. Request already timed out (future was removed by orTimeout)
                // 2. Duplicate response from server (should not happen)
                log.warn("⚠️ Unmatched CCA: hopByHop=0x{} (may have timed out)",
                         Long.toHexString(hopByHop));
            }

        } else if (commandCode == AvpCodes.CMD_CAPABILITIES_EXCHANGE && !message.isRequest()) {
            // CEA received — CER/CEA handshake complete
            long resultCode = message.findAvp(AvpCodes.RESULT_CODE)
                                     .map(codec::readUint32)
                                     .orElse(0L);
            if (resultCode == 2001) {
                cerCompleted.set(true);
                log.info("✅ CEA received: resultCode=2001 — Diameter session ready");
            } else {
                log.error("❌ CEA rejected: resultCode={}", resultCode);
            }

        } else if (commandCode == AvpCodes.CMD_DEVICE_WATCHDOG && !message.isRequest()) {
            log.debug("💓 DWA received — connection healthy");

        } else if (commandCode == AvpCodes.CMD_RE_AUTH && message.isRequest()) {
            // Server-initiated Re-Auth-Request — send answer
            handleRar(message);
        }
    }

    /**
     * Called by DiameterClientHandler.channelActive() — AFTER Netty registers OP_READ.
     *
     * WHY HERE and not in the connect listener?
     * The connect ChannelFuture listener fires BEFORE channelActive() propagates
     * through the pipeline, which means OP_READ isn't registered yet when the
     * listener runs. If we send CER there, the CEA response arrives at the OS
     * socket buffer but Netty's NIO selector hasn't started watching for reads,
     * and on some platforms the selector misses the notification.
     *
     * channelActive() fires only after HeadContext.readIfIsAutoRead() registers
     * OP_READ with the NIO selector — so CEA bytes will always be received.
     */
    public void onChannelActive(Channel ch) {
        channel = ch;
        connected.set(true);
        log.info("🔌 Channel active — sending CER handshake");
        sendCer();
        startWatchdog();
    }

    /**
     * Called by DiameterClientHandler when TCP connection is lost.
     * Completes all pending futures with error and attempts reconnect.
     */
    public void onConnectionLost() {
        log.warn("⚠️ Connection to Diameter server lost! Pending requests: {}",
                 pendingRequests.size());
        connected.set(false);
        cerCompleted.set(false);

        // Fail all pending requests immediately
        pendingRequests.forEach((hopByHop, future) -> {
            future.completeExceptionally(
                new DiameterException("Diameter connection lost", 503)
            );
        });
        pendingRequests.clear();

        // Attempt reconnection after delay
        scheduleReconnect();
    }

    // ═══════════════════════════════════════════════════════════════════
    // MESSAGE BUILDERS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Builds a CER (Capabilities-Exchange-Request).
     *
     * CER announces our capabilities to the Diameter server.
     * MANDATORY AVPs per RFC 6733 §5.3.1:
     *   - Origin-Host
     *   - Origin-Realm
     *   - Host-IP-Address
     *   - Vendor-Id
     *   - Product-Name
     *   - Auth-Application-Id (what applications we support)
     */
    private void sendCer() {
        long hopByHop = hopByHopCounter.incrementAndGet() & 0xFFFFFFFFL;
        long endToEnd = System.currentTimeMillis() & 0xFFFFFFFFL;

        DiameterHeader header = DiameterHeader.request(
            AvpCodes.CMD_CAPABILITIES_EXCHANGE,
            0L,  // Application-ID = 0 for base protocol messages
            hopByHop, endToEnd
        );

        DiameterMessage cer = new DiameterMessage(header);
        cer.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_HOST, true, originHost));
        cer.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_REALM, true, originRealm));
        cer.addAvp(codec.buildAddressAvp(AvpCodes.HOST_IP_ADDRESS, true, "127.0.0.1"));
        cer.addAvp(codec.buildUint32Avp(AvpCodes.VENDOR_ID, true, AvpCodes.VENDOR_IETF));
        cer.addAvp(codec.buildUtf8Avp(AvpCodes.PRODUCT_NAME, false, "TelecomBridgeGateway"));
        cer.addAvp(codec.buildUint32Avp(AvpCodes.AUTH_APPLICATION_ID, false,
                                         AvpCodes.APP_CREDIT_CONTROL)); // Support Credit Control (4)

        channel.writeAndFlush(cer);
        log.info("📤 CER sent — waiting for CEA...");
    }

    /**
     * Builds a complete Credit-Control-Request (CCR) message.
     *
     * MANDATORY AVPs for CCR per RFC 4006 §8.3:
     *   Session-Id, Origin-Host, Origin-Realm, Destination-Realm
     *   Auth-Application-Id, CC-Request-Type, CC-Request-Number
     * RECOMMENDED: Subscription-Id, Requested-Service-Unit
     */
    private DiameterMessage buildCcr(String sessionId, long hopByHop,
                                      String msisdn, long requestedUnits,
                                      String currency) {
        DiameterHeader header = DiameterHeader.request(
            AvpCodes.CMD_CREDIT_CONTROL,
            AvpCodes.APP_CREDIT_CONTROL,
            hopByHop,
            hopByHop // end-to-end same as hop-by-hop for simplicity
        );

        DiameterMessage ccr = new DiameterMessage(header);

        // Session-Id MUST be first AVP
        ccr.addAvp(codec.buildUtf8Avp(AvpCodes.SESSION_ID, true, sessionId));

        // Routing AVPs
        ccr.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_HOST, true, originHost));
        ccr.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_REALM, true, originRealm));
        ccr.addAvp(codec.buildUtf8Avp(AvpCodes.DESTINATION_REALM, true, originRealm));

        // Application and request type
        ccr.addAvp(codec.buildUint32Avp(AvpCodes.AUTH_APPLICATION_ID, true,
                                         AvpCodes.APP_CREDIT_CONTROL));
        ccr.addAvp(codec.buildUint32Avp(AvpCodes.CC_REQUEST_TYPE, true,
                                         AvpCodes.CC_REQUEST_TYPE_EVENT)); // EVENT = one-shot
        ccr.addAvp(codec.buildUint32Avp(AvpCodes.CC_REQUEST_NUMBER, true, 0L));

        // Subscriber identity — who is being charged
        ccr.addAvp(codec.buildGroupedAvp(AvpCodes.SUBSCRIPTION_ID, true,
            codec.buildUint32Avp(AvpCodes.SUBSCRIPTION_ID_TYPE, true,
                                 AvpCodes.SUBSCRIPTION_ID_TYPE_E164), // E164 = phone number
            codec.buildUtf8Avp(AvpCodes.SUBSCRIPTION_ID_DATA, true, msisdn)
        ));

        // What we're requesting to charge
        ccr.addAvp(codec.buildGroupedAvp(AvpCodes.REQUESTED_SERVICE_UNIT, false,
            codec.buildUint64Avp(AvpCodes.CC_SERVICE_SPECIFIC_UNITS, false, requestedUnits)
        ));

        // Service context — identifies what service is being charged
        ccr.addAvp(codec.buildUtf8Avp(AvpCodes.SERVICE_CONTEXT_ID, true,
                                       "telecharge@telecom.com"));

        // Requested action: direct debiting (deduct from account)
        ccr.addAvp(codec.buildUint32Avp(AvpCodes.REQUESTED_ACTION, false,
                                         AvpCodes.REQUESTED_ACTION_DIRECT_DEBITING));

        return ccr;
    }

    // ═══════════════════════════════════════════════════════════════════
    // WATCHDOG — Keep-alive mechanism
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Starts the Device Watchdog timer.
     * Sends DWR every watchdogIntervalSeconds seconds.
     *
     * WHY WE NEED WATCHDOG:
     * NAT firewalls, load balancers, and cloud infrastructure close
     * idle TCP connections silently after 5-15 minutes.
     * The application sees the connection as still open (no RST packet).
     * Next CCR write silently fails or hangs.
     * DWR prevents this by keeping the connection "warm".
     */
    private void startWatchdog() {
        watchdogScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "diameter-watchdog");
            t.setDaemon(true);
            return t;
        });

        watchdogScheduler.scheduleAtFixedRate(
            this::sendDwr,
            watchdogIntervalSeconds,  // initial delay
            watchdogIntervalSeconds,  // period
            TimeUnit.SECONDS
        );

        log.info("💓 Watchdog started (DWR every {}s)", watchdogIntervalSeconds);
    }

    private void sendDwr() {
        if (!connected.get() || channel == null || !channel.isActive()) {
            return;
        }

        long hopByHop = hopByHopCounter.incrementAndGet() & 0xFFFFFFFFL;
        DiameterHeader header = DiameterHeader.request(
            AvpCodes.CMD_DEVICE_WATCHDOG, 0L, hopByHop, hopByHop
        );

        DiameterMessage dwr = new DiameterMessage(header);
        dwr.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_HOST, true, originHost));
        dwr.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_REALM, true, originRealm));

        channel.writeAndFlush(dwr);
        log.debug("💓 DWR sent");
    }

    private void sendDpr() {
        long hopByHop = hopByHopCounter.incrementAndGet() & 0xFFFFFFFFL;
        DiameterHeader header = DiameterHeader.request(
            AvpCodes.CMD_DISCONNECT_PEER, 0L, hopByHop, hopByHop
        );
        DiameterMessage dpr = new DiameterMessage(header);
        dpr.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_HOST, true, originHost));
        dpr.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_REALM, true, originRealm));
        dpr.addAvp(codec.buildUint32Avp(AvpCodes.DISCONNECT_CAUSE, false, 0L)); // REBOOTING

        channel.writeAndFlush(dpr);
    }

    // ═══════════════════════════════════════════════════════════════════
    // RECONNECTION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Schedules a reconnection attempt after a delay.
     *
     * Uses a single daemon thread (not the Netty event loop) so it doesn't
     * block I/O processing. The delay gives the Diameter server time to
     * restart before we hammer it with reconnect attempts.
     *
     * WHY NOT exponential backoff?
     * For a telecom gateway, a fixed 5s retry is predictable and testable.
     * Real production systems use exponential backoff with jitter, but
     * that adds complexity beyond this project's scope.
     */
    private void scheduleReconnect() {
        log.info("🔄 Scheduling reconnection in 5 seconds...");
        // Use a daemon thread so it doesn't prevent JVM shutdown
        Thread retryThread = new Thread(() -> {
            try {
                Thread.sleep(5000);
                doConnect(); // Reuse existing eventLoopGroup — no thread leak
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("❌ Reconnection attempt failed: {}", e.getMessage());
                scheduleReconnect();
            }
        }, "diameter-reconnect");
        retryThread.setDaemon(true);
        retryThread.start();
    }

    private void handleRar(DiameterMessage rar) {
        // Server-initiated Re-Auth — respond with RAA
        DiameterHeader raaHeader = DiameterHeader.answer(
            AvpCodes.CMD_RE_AUTH, AvpCodes.APP_CREDIT_CONTROL,
            rar.getHopByHopId(), rar.getHeader().getEndToEndId()
        );
        DiameterMessage raa = new DiameterMessage(raaHeader);
        raa.addAvp(codec.buildUint32Avp(AvpCodes.RESULT_CODE, true, 2001L));
        raa.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_HOST, true, originHost));
        raa.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_REALM, true, originRealm));
        channel.writeAndFlush(raa);
    }

    // ═══════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Generates a unique Diameter Session-ID.
     * Format per RFC 6733: "<DiameterIdentity>;<high32bits>;<low32bits>"
     * Example: "gateway.telecom.com;1717200000;42"
     */
    private String generateSessionId() {
        long now = System.currentTimeMillis() / 1000; // Unix timestamp
        long seq = sessionCounter.incrementAndGet();
        return String.format("SID;%s;%d;%d", originHost, now, seq);
    }

    private String maskMsisdn(String msisdn) {
        if (msisdn == null || msisdn.length() < 8) return "****";
        return msisdn.substring(0, 4) + "***" + msisdn.substring(msisdn.length() - 4);
    }

    // ─── Status accessors for health checks ───────────────────────────
    public boolean isConnected() { return connected.get(); }
    public boolean isReady() { return connected.get() && cerCompleted.get(); }
    public int getPendingRequestCount() { return pendingRequests.size(); }
}
