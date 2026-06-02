package com.telecom.gateway.diameter.handler;

import com.telecom.gateway.diameter.client.DiameterClient;
import com.telecom.gateway.diameter.codec.DiameterCodec;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

/**
 * DiameterClientInitializer — Sets up the Netty pipeline for the client connection.
 *
 * Same pipeline concept as the server, but from the CLIENT perspective:
 *   [TCP bytes in] → [FrameDecoder] → [Decoder] → [ClientHandler] → [Encoder] → [TCP bytes out]
 */
public class DiameterClientInitializer extends ChannelInitializer<SocketChannel> {

    private final DiameterClient diameterClient;
    private final DiameterCodec codec;

    public DiameterClientInitializer(DiameterClient diameterClient, DiameterCodec codec) {
        this.diameterClient = diameterClient;
        this.codec = codec;
    }

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();

        // Debug: log raw bytes at the network level (DEBUG level)
        pipeline.addLast("logger", new LoggingHandler("DiameterWire", LogLevel.DEBUG));

        // Assemble complete Diameter frames from TCP stream
        pipeline.addLast("frameDecoder",
            new LengthFieldBasedFrameDecoder(65535, 1, 3, -4, 0));

        // Decode bytes → DiameterMessage
        pipeline.addLast("messageDecoder", new ClientMessageDecoder(codec));

        // Business logic: route CEA, CCA, DWA to DiameterClient methods
        pipeline.addLast("clientHandler", new DiameterClientHandler(diameterClient));

        // Encode DiameterMessage → bytes for sending
        pipeline.addLast("messageEncoder", new ClientMessageEncoder(codec));
    }
}
