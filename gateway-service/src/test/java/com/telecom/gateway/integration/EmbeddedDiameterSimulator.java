package com.telecom.gateway.integration;

import com.telecom.gateway.diameter.codec.DiameterCodec;
import com.telecom.gateway.model.diameter.AvpCodes;
import com.telecom.gateway.model.diameter.DiameterHeader;
import com.telecom.gateway.model.diameter.DiameterMessage;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * EmbeddedDiameterSimulator — A minimal in-process Diameter server for integration tests.
 *
 * WHY IN-PROCESS?
 * Integration tests should be self-contained — no external processes to start/stop.
 * This simulator runs in the same JVM as the test, sharing the same classpath.
 * It handles CER/CEA and CCR/CCA exactly like the standalone simulator.
 *
 * It does NOT simulate the 50-100ms delay (tests should be fast).
 *
 * USAGE:
 *   EmbeddedDiameterSimulator sim = new EmbeddedDiameterSimulator(13868);
 *   sim.start();
 *   // ... run tests ...
 *   sim.stop();
 */
public class EmbeddedDiameterSimulator {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedDiameterSimulator.class);

    private final int port;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private final DiameterCodec codec = new DiameterCodec();

    public EmbeddedDiameterSimulator(int port) {
        this.port = port;
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(2);

        ServerBootstrap b = new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(
                        new LengthFieldBasedFrameDecoder(65535, 1, 3, -4, 0),
                        new DiameterDecoder(),
                        new SimHandler(),
                        new DiameterEncoder()
                    );
                }
            });

        serverChannel = b.bind(port).sync().channel();
        log.info("🧪 EmbeddedDiameterSimulator started on port {}", port);
    }

    public void stop() {
        if (serverChannel != null) serverChannel.close();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (bossGroup != null) bossGroup.shutdownGracefully();
        log.info("🧪 EmbeddedDiameterSimulator stopped");
    }

    // ─── Diameter decoder ────────────────────────────────────────────
    private class DiameterDecoder extends MessageToMessageDecoder<ByteBuf> {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            byte[] bytes = new byte[in.readableBytes()];
            in.readBytes(bytes);
            out.add(codec.decode(bytes));
        }
    }

    // ─── Diameter encoder ────────────────────────────────────────────
    private class DiameterEncoder extends MessageToByteEncoder<DiameterMessage> {
        @Override
        protected void encode(ChannelHandlerContext ctx, DiameterMessage msg, ByteBuf out) {
            out.writeBytes(codec.encode(msg));
        }
    }

    // ─── Business logic ──────────────────────────────────────────────
    private class SimHandler extends SimpleChannelInboundHandler<DiameterMessage> {

        private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "test-sim-delay");
                t.setDaemon(true);
                return t;
            });

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DiameterMessage msg) {
            int cmd = msg.getCommandCode();

            if (cmd == AvpCodes.CMD_CAPABILITIES_EXCHANGE && msg.isRequest()) {
                handleCer(ctx, msg);
            } else if (cmd == AvpCodes.CMD_DEVICE_WATCHDOG && msg.isRequest()) {
                handleDwr(ctx, msg);
            } else if (cmd == AvpCodes.CMD_CREDIT_CONTROL && msg.isRequest()) {
                handleCcr(ctx, msg);
            } else if (cmd == AvpCodes.CMD_DISCONNECT_PEER && msg.isRequest()) {
                ctx.close();
            }
        }

        private void handleCer(ChannelHandlerContext ctx, DiameterMessage cer) {
            DiameterMessage cea = new DiameterMessage(DiameterHeader.answer(
                AvpCodes.CMD_CAPABILITIES_EXCHANGE, 0L,
                cer.getHeader().getHopByHopId(), cer.getHeader().getEndToEndId()
            ));
            cea.addAvp(codec.buildUint32Avp(AvpCodes.RESULT_CODE, true, 2001L));
            cea.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_HOST, true, "test-sim.local"));
            cea.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_REALM, true, "local"));
            cea.addAvp(codec.buildAddressAvp(AvpCodes.HOST_IP_ADDRESS, true, "127.0.0.1"));
            cea.addAvp(codec.buildUint32Avp(AvpCodes.VENDOR_ID, true, 0L));
            cea.addAvp(codec.buildUtf8Avp(AvpCodes.PRODUCT_NAME, false, "TestSimulator"));
            cea.addAvp(codec.buildUint32Avp(AvpCodes.AUTH_APPLICATION_ID, false, 4L));
            ctx.channel().writeAndFlush(cea);
        }

        private void handleDwr(ChannelHandlerContext ctx, DiameterMessage dwr) {
            DiameterMessage dwa = new DiameterMessage(DiameterHeader.answer(
                AvpCodes.CMD_DEVICE_WATCHDOG, 0L,
                dwr.getHeader().getHopByHopId(), dwr.getHeader().getEndToEndId()
            ));
            dwa.addAvp(codec.buildUint32Avp(AvpCodes.RESULT_CODE, true, 2001L));
            dwa.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_HOST, true, "test-sim.local"));
            dwa.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_REALM, true, "local"));
            ctx.channel().writeAndFlush(dwa);
        }

        private void handleCcr(ChannelHandlerContext ctx, DiameterMessage ccr) {
            // Extract requested units from CCR to echo back as granted
            long requestedUnits = ccr.findAvp(AvpCodes.REQUESTED_SERVICE_UNIT)
                .map(avp -> {
                    try {
                        DiameterMessage rsu = codec.readGrouped(avp);
                        return rsu.findAvp(AvpCodes.CC_SERVICE_SPECIFIC_UNITS)
                            .map(codec::readUint64).orElse(100L);
                    } catch (Exception e) { return 100L; }
                }).orElse(100L);

            String sessionId = ccr.findAvp(AvpCodes.SESSION_ID)
                .map(codec::readUtf8).orElse("UNKNOWN");

            // No artificial delay in tests — respond immediately
            DiameterMessage cca = buildCca(ccr, sessionId, requestedUnits);
            ctx.channel().writeAndFlush(cca);
        }

        private DiameterMessage buildCca(DiameterMessage ccr, String sessionId, long units) {
            DiameterMessage cca = new DiameterMessage(DiameterHeader.answer(
                AvpCodes.CMD_CREDIT_CONTROL, AvpCodes.APP_CREDIT_CONTROL,
                ccr.getHeader().getHopByHopId(), ccr.getHeader().getEndToEndId()
            ));
            cca.addAvp(codec.buildUtf8Avp(AvpCodes.SESSION_ID, true, sessionId));
            cca.addAvp(codec.buildUint32Avp(AvpCodes.RESULT_CODE, true, 2001L));
            cca.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_HOST, true, "test-sim.local"));
            cca.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_REALM, true, "local"));
            cca.addAvp(codec.buildUint32Avp(AvpCodes.AUTH_APPLICATION_ID, false, 4L));
            cca.addAvp(codec.buildUint32Avp(AvpCodes.CC_REQUEST_TYPE, true, 4L));
            cca.addAvp(codec.buildUint32Avp(AvpCodes.CC_REQUEST_NUMBER, true, 0L));

            // Granted-Service-Unit grouped AVP
            cca.addAvp(codec.buildGroupedAvp(AvpCodes.GRANTED_SERVICE_UNIT, false,
                codec.buildUint64Avp(AvpCodes.CC_SERVICE_SPECIFIC_UNITS, false, units)));

            return cca;
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("TestSimulator error: {}", cause.getMessage());
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            scheduler.shutdown();
        }
    }
}
