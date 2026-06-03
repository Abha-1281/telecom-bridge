package com.telecom.simulator.handler;

import com.telecom.simulator.codec.SimulatorCodec;
import com.telecom.simulator.model.AvpCodes;
import com.telecom.simulator.model.DiameterHeader;
import com.telecom.simulator.model.DiameterMessage;
import com.telecom.simulator.model.DiameterMessage.Avp;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DiameterServerHandler — The core business logic of the simulator.
 *
 * Handles all three Diameter message types:
 *
 * 1. CER (Capabilities-Exchange-Request) → CEA
 *    Happens once when client first connects.
 *    Like a login/handshake — "what can you do? what can I do?"
 *
 * 2. DWR (Device-Watchdog-Request) → DWA
 *    Happens every 30 seconds while connected.
 *    Like a heartbeat: "are you still alive?" → "yes!"
 *    Without this, firewalls close idle TCP connections silently.
 *
 * 3. CCR (Credit-Control-Request) → CCA
 *    The actual billing request.
 *    We simulate real processing delay of 50-100ms.
 *
 * THREAD SAFETY:
 * Each Netty channel has its own handler instance.
 * The handler is called on the channel's event loop thread.
 * We use a scheduled executor for the async CCR delay.
 *
 * @Sharable annotation: NOT used here because we have per-connection state.
 * Netty creates one handler instance per connection (via Initializer).
 */
public class DiameterServerHandler extends SimpleChannelInboundHandler<DiameterMessage> {

    private static final Logger log = LoggerFactory.getLogger(DiameterServerHandler.class);

    // Simulate realistic OCS processing delay: 5-20ms random
    // Kept low so end-to-end p95 latency stays under the 100ms SLA target.
    // Real OCS systems typically respond in 5-30ms on local network.
    private static final int MIN_DELAY_MS = 5;
    private static final int MAX_DELAY_MS = 20;
    private static final Random RANDOM = new Random();

    // Our server identity — what we tell clients in CER/CEA
    private static final String ORIGIN_HOST  = "simulator.telecom.com";
    private static final String ORIGIN_REALM = "telecom.com";

    // Counter for tracking total requests handled
    private static final AtomicLong totalCcrCount = new AtomicLong(0);

    // Scheduled executor for async CCR delay simulation
    // Each handler gets its own thread to avoid cross-connection interference
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "diameter-delay-" + System.nanoTime() % 1000);
            t.setDaemon(true);
            return t;
        });

    private final SimulatorCodec codec;

    public DiameterServerHandler(SimulatorCodec codec) {
        this.codec = codec;
    }

    // ═══════════════════════════════════════════════════════════════════
    // CONNECTION LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        String clientAddr = ((InetSocketAddress) ctx.channel().remoteAddress())
                                .getAddress().getHostAddress();
        log.info("📡 New Diameter client connected: {}", clientAddr);
        // Note: We don't initiate CER — the CLIENT must send CER first (RFC 6733 §5.3)
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("🔌 Diameter client disconnected. Total CCRs handled: {}", totalCcrCount.get());
        scheduler.shutdown();
    }

    // ═══════════════════════════════════════════════════════════════════
    // MESSAGE ROUTING — Dispatch to correct handler
    // ═══════════════════════════════════════════════════════════════════

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DiameterMessage message)
            throws Exception {
        int commandCode = message.getCommandCode();
        boolean isRequest = message.isRequest();

        log.debug("📨 Received: cmd={} hopByHop=0x{} request={}",
                  commandCode, Long.toHexString(message.getHopByHopId()), isRequest);

        // Route to appropriate handler based on command code
        if (commandCode == AvpCodes.CMD_CAPABILITIES_EXCHANGE && isRequest) {
            handleCer(ctx, message);
        } else if (commandCode == AvpCodes.CMD_DEVICE_WATCHDOG && isRequest) {
            handleDwr(ctx, message);
        } else if (commandCode == AvpCodes.CMD_CREDIT_CONTROL && isRequest) {
            handleCcr(ctx, message);
        } else if (commandCode == AvpCodes.CMD_DISCONNECT_PEER && isRequest) {
            handleDpr(ctx, message);
        } else {
            log.warn("⚠️ Unhandled message: cmd={} request={}", commandCode, isRequest);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CER → CEA (Capabilities Exchange)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Handles Capabilities-Exchange-Request (CER).
     *
     * CER is the FIRST message after TCP connection is established.
     * The client announces: "I support these applications and features."
     * We respond with CEA: "OK, I support these too. Connection accepted."
     *
     * MANDATORY AVPs in CEA (RFC 6733 §5.3.2):
     *   - Result-Code (2001 = success)
     *   - Origin-Host
     *   - Origin-Realm
     *   - Host-IP-Address
     *   - Vendor-Id
     *   - Product-Name
     *
     * After successful CEA, the client can start sending CCR messages.
     */
    private void handleCer(ChannelHandlerContext ctx, DiameterMessage cer) {
        log.info("🤝 CER received → sending CEA (Result-Code: 2001 SUCCESS)");

        // Build CEA header
        // Command code same as CER (257), but R flag = 0 (it's an answer)
        DiameterHeader ceaHeader = DiameterHeader.answer(
            AvpCodes.CMD_CAPABILITIES_EXCHANGE,
            0L, // Application-ID = 0 for base protocol messages
            cer.getHeader().getHopByHopId(), // SAME hop-by-hop as request
            cer.getHeader().getEndToEndId()  // SAME end-to-end as request
        );

        DiameterMessage cea = new DiameterMessage(ceaHeader);

        // Mandatory AVPs
        cea.addAvp(codec.buildUint32Avp(AvpCodes.RESULT_CODE, true, 2001L));
        cea.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_HOST, true, ORIGIN_HOST));
        cea.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_REALM, true, ORIGIN_REALM));
        cea.addAvp(codec.buildAddressAvp(AvpCodes.HOST_IP_ADDRESS, true, "127.0.0.1"));
        cea.addAvp(codec.buildUint32Avp(AvpCodes.VENDOR_ID, true, AvpCodes.VENDOR_IETF));
        cea.addAvp(codec.buildUtf8Avp(AvpCodes.PRODUCT_NAME, false, "TelecomBridgeSimulator"));

        // Announce Credit Control application support
        cea.addAvp(codec.buildUint32Avp(AvpCodes.AUTH_APPLICATION_ID, false,
                                         AvpCodes.APP_CREDIT_CONTROL));

        ctx.channel().writeAndFlush(cea);
        log.info("✅ CEA sent — connection established");
    }

    // ═══════════════════════════════════════════════════════════════════
    // DWR → DWA (Device Watchdog — Keep-Alive)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Handles Device-Watchdog-Request (DWR).
     *
     * Purpose: Verify the TCP connection is still alive.
     * Without this, NAT firewalls and load balancers silently drop
     * idle TCP connections after a timeout (typically 5-15 minutes).
     *
     * The client sends DWR every 30 seconds.
     * We MUST respond immediately with DWA.
     * If we don't respond within a timeout, client closes and reconnects.
     *
     * MANDATORY AVPs in DWA (RFC 6733 §5.5.2):
     *   - Result-Code
     *   - Origin-Host
     *   - Origin-Realm
     */
    private void handleDwr(ChannelHandlerContext ctx, DiameterMessage dwr) {
        log.debug("💓 DWR received → sending DWA (watchdog pong)");

        DiameterHeader dwaHeader = DiameterHeader.answer(
            AvpCodes.CMD_DEVICE_WATCHDOG,
            0L, // base protocol
            dwr.getHeader().getHopByHopId(),
            dwr.getHeader().getEndToEndId()
        );

        DiameterMessage dwa = new DiameterMessage(dwaHeader);
        dwa.addAvp(codec.buildUint32Avp(AvpCodes.RESULT_CODE, true, 2001L));
        dwa.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_HOST, true, ORIGIN_HOST));
        dwa.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_REALM, true, ORIGIN_REALM));

        ctx.channel().writeAndFlush(dwa);
        // Log at DEBUG level — DWRs happen every 30s, INFO would be too noisy
    }

    // ═══════════════════════════════════════════════════════════════════
    // CCR → CCA (Credit Control — The Main Business Logic)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Handles Credit-Control-Request (CCR).
     *
     * This is the core of the simulator — what the gateway actually calls.
     *
     * SIMULATED BEHAVIOR:
     * 1. Extract key fields from CCR (Session-Id, MSISDN, requested units)
     * 2. Wait 50-100ms (simulates real OCS processing: DB lookup, quota check)
     * 3. Always return SUCCESS (2001) with full granted units
     *    (In a real OCS: would check balance, deduct, may return 4012 INSUFFICIENT)
     *
     * MANDATORY AVPs in CCA (RFC 4006 §8.4):
     *   - Session-Id (same as CCR)
     *   - Result-Code
     *   - Origin-Host
     *   - Origin-Realm
     *   - Auth-Application-Id (4 = Credit Control)
     *   - CC-Request-Type (same as CCR)
     *   - CC-Request-Number (same as CCR)
     *
     * CRITICAL: The CCA MUST have the SAME Hop-by-Hop ID as the CCR.
     * This is how the gateway matches the answer to the pending request.
     *
     * @param ctx Netty channel context — use this to send the response
     * @param ccr The incoming Credit Control Request
     */
    private void handleCcr(ChannelHandlerContext ctx, DiameterMessage ccr) {
        long requestCount = totalCcrCount.incrementAndGet();
        long hopByHop = ccr.getHeader().getHopByHopId();

        // Extract fields from CCR for logging and response
        String sessionId = ccr.findAvp(AvpCodes.SESSION_ID)
                              .map(codec::readUtf8)
                              .orElse("UNKNOWN-SESSION");

        long requestType = ccr.findAvp(AvpCodes.CC_REQUEST_TYPE)
                              .map(codec::readUint32)
                              .orElse(4L);

        long requestNumber = ccr.findAvp(AvpCodes.CC_REQUEST_NUMBER)
                                .map(codec::readUint32)
                                .orElse(0L);

        // Try to extract MSISDN from Subscription-Id grouped AVP
        String msisdn = extractMsisdn(ccr);

        // Try to extract requested units
        long requestedUnits = extractRequestedUnits(ccr);

        log.info("💳 CCR #{} received: hopByHop=0x{} msisdn={} units={} session={}",
                 requestCount,
                 Long.toHexString(hopByHop),
                 maskMsisdn(msisdn),
                 requestedUnits,
                 sessionId);

        // ─── Simulate processing delay ─────────────────────────────────
        // In a real OCS: database lookup, fraud check, quota calculation
        // We use a scheduler to avoid blocking the Netty I/O thread!
        // NEVER call Thread.sleep() on a Netty thread — it blocks all other requests
        int delayMs = MIN_DELAY_MS + RANDOM.nextInt(MAX_DELAY_MS - MIN_DELAY_MS);

        // Capture final values for lambda
        final String capturedSessionId = sessionId;
        final long capturedRequestType = requestType;
        final long capturedRequestNumber = requestNumber;
        final long capturedRequestedUnits = requestedUnits;

        scheduler.schedule(() -> {
            try {
                // Build CCA response
                DiameterMessage cca = buildCca(
                    ccr, capturedSessionId, capturedRequestType,
                    capturedRequestNumber, capturedRequestedUnits
                );

                // Send response back to client
                // ctx.channel().writeAndFlush() is thread-safe — Netty queues the write
                ctx.channel().writeAndFlush(cca);

                log.info("✅ CCA #{} sent: hopByHop=0x{} granted={} delay={}ms",
                         requestCount,
                         Long.toHexString(hopByHop),
                         capturedRequestedUnits,
                         delayMs);

            } catch (Exception e) {
                log.error("❌ Error building CCA for hopByHop=0x{}: {}",
                          Long.toHexString(hopByHop), e.getMessage(), e);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Builds the CCA (Credit Control Answer) message.
     * The gateway's async client will match this to the pending CCR
     * using the Hop-by-Hop ID.
     */
    private DiameterMessage buildCca(DiameterMessage ccr, String sessionId,
                                      long requestType, long requestNumber,
                                      long grantedUnits) {
        // Answer header: same command code, R flag = 0, SAME hop-by-hop ID
        DiameterHeader ccaHeader = DiameterHeader.answer(
            AvpCodes.CMD_CREDIT_CONTROL,
            AvpCodes.APP_CREDIT_CONTROL,
            ccr.getHeader().getHopByHopId(), // ← CRITICAL: same as CCR
            ccr.getHeader().getEndToEndId()
        );

        DiameterMessage cca = new DiameterMessage(ccaHeader);

        // Mandatory AVPs per RFC 4006 §8.4
        cca.addAvp(codec.buildUtf8Avp(AvpCodes.SESSION_ID, true, sessionId));
        cca.addAvp(codec.buildUint32Avp(AvpCodes.RESULT_CODE, true, 2001L)); // SUCCESS
        cca.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_HOST, true, ORIGIN_HOST));
        cca.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_REALM, true, ORIGIN_REALM));
        cca.addAvp(codec.buildUint32Avp(AvpCodes.AUTH_APPLICATION_ID, true,
                                         AvpCodes.APP_CREDIT_CONTROL));
        cca.addAvp(codec.buildUint32Avp(AvpCodes.CC_REQUEST_TYPE, true, requestType));
        cca.addAvp(codec.buildUint32Avp(AvpCodes.CC_REQUEST_NUMBER, true, requestNumber));

        // Granted-Service-Unit — how much we're approving
        // We always approve the full requested amount (simplified simulator)
        Avp grantedServiceUnit = codec.buildGroupedAvp(
            AvpCodes.GRANTED_SERVICE_UNIT, true,
            codec.buildUint64Avp(AvpCodes.CC_SERVICE_SPECIFIC_UNITS, false, grantedUnits)
        );
        cca.addAvp(grantedServiceUnit);

        // Validity-Time: granted quota valid for 300 seconds
        cca.addAvp(codec.buildUint32Avp(AvpCodes.VALIDITY_TIME, false, 300L));

        return cca;
    }

    // ═══════════════════════════════════════════════════════════════════
    // DPR → DPA (Disconnect Peer)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Handles Disconnect-Peer-Request (DPR).
     * Client is gracefully closing the Diameter connection.
     * We send DPA then close the TCP connection.
     */
    private void handleDpr(ChannelHandlerContext ctx, DiameterMessage dpr) {
        log.info("👋 DPR received — client is disconnecting gracefully");

        DiameterHeader dpaHeader = DiameterHeader.answer(
            AvpCodes.CMD_DISCONNECT_PEER,
            0L,
            dpr.getHeader().getHopByHopId(),
            dpr.getHeader().getEndToEndId()
        );

        DiameterMessage dpa = new DiameterMessage(dpaHeader);
        dpa.addAvp(codec.buildUint32Avp(AvpCodes.RESULT_CODE, true, 2001L));
        dpa.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_HOST, true, ORIGIN_HOST));
        dpa.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_REALM, true, ORIGIN_REALM));

        ctx.channel().writeAndFlush(dpa).addListener(f -> ctx.close());
    }

    // ═══════════════════════════════════════════════════════════════════
    // ERROR HANDLING
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("❌ Exception in Diameter connection: {}", cause.getMessage(), cause);
        ctx.close(); // Close connection on unhandled exception
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            log.warn("⏱️ Connection idle for 60s — closing stale connection");
            ctx.close();
        }
        super.userEventTriggered(ctx, evt);
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Extracts MSISDN from the Subscription-Id grouped AVP.
     *
     * Subscription-Id is a grouped AVP containing:
     *   - Subscription-Id-Type (should be E164 = 0)
     *   - Subscription-Id-Data (the actual phone number)
     */
    private String extractMsisdn(DiameterMessage ccr) {
        Optional<Avp> subscriptionIdAvp = ccr.findAvp(AvpCodes.SUBSCRIPTION_ID);
        if (subscriptionIdAvp.isEmpty()) {
            return "UNKNOWN";
        }

        try {
            DiameterMessage children = codec.readGrouped(subscriptionIdAvp.get());
            return children.findAvp(AvpCodes.SUBSCRIPTION_ID_DATA)
                           .map(codec::readUtf8)
                           .orElse("UNKNOWN");
        } catch (Exception e) {
            return "PARSE_ERROR";
        }
    }

    /**
     * Extracts requested units from Requested-Service-Unit grouped AVP.
     * Falls back to 100 if not present (for backward compatibility).
     */
    private long extractRequestedUnits(DiameterMessage ccr) {
        Optional<Avp> rsuAvp = ccr.findAvp(AvpCodes.REQUESTED_SERVICE_UNIT);
        if (rsuAvp.isEmpty()) return 100L;

        try {
            DiameterMessage children = codec.readGrouped(rsuAvp.get());
            return children.findAvp(AvpCodes.CC_SERVICE_SPECIFIC_UNITS)
                           .map(codec::readUint64)
                           .orElseGet(() ->
                               children.findAvp(AvpCodes.CC_TOTAL_OCTETS)
                                       .map(codec::readUint64)
                                       .orElse(100L)
                           );
        } catch (Exception e) {
            return 100L;
        }
    }

    /** Masks MSISDN for privacy in logs: "919876543210" → "9198***3210" */
    private String maskMsisdn(String msisdn) {
        if (msisdn == null || msisdn.length() < 8) return "****";
        return msisdn.substring(0, 4) + "***" + msisdn.substring(msisdn.length() - 4);
    }
}
