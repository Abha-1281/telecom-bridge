package com.telecom.gateway.diameter.handler;

import com.telecom.gateway.diameter.client.DiameterClient;
import com.telecom.gateway.model.diameter.DiameterMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DiameterClientHandler — Netty handler that bridges incoming messages to DiameterClient.
 *
 * All incoming Diameter messages (CEA, CCA, DWA) flow through here.
 * This handler runs on the Netty I/O thread.
 * It delegates immediately to DiameterClient which completes the futures.
 *
 * KEY: channelActive() is the right place to send CER — at this point
 * the channel is fully registered for OP_READ, so the CEA response
 * will be received correctly.
 *
 * IMPORTANT: Never block this handler. No Thread.sleep(), no blocking I/O.
 */
public class DiameterClientHandler extends SimpleChannelInboundHandler<DiameterMessage> {

    private static final Logger log = LoggerFactory.getLogger(DiameterClientHandler.class);

    private final DiameterClient diameterClient;

    public DiameterClientHandler(DiameterClient diameterClient) {
        this.diameterClient = diameterClient;
    }

    /**
     * Called when TCP connection is fully established AND OP_READ is registered.
     * This is the correct place to send CER — the channel can now receive the CEA reply.
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.debug("Channel active — initiating CER handshake");
        diameterClient.onChannelActive(ctx.channel());
        super.channelActive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DiameterMessage message) {
        // Delegate to DiameterClient — it knows what to do with each message type
        diameterClient.onMessageReceived(message);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // TCP connection was closed (server went down, network issue, etc.)
        log.warn("⚠️ TCP connection to Diameter server lost");
        diameterClient.onConnectionLost();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("❌ Diameter client exception: {}", cause.getMessage(), cause);
        ctx.close();
    }
}
