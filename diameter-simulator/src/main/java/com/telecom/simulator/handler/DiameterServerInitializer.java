package com.telecom.simulator.handler;

import com.telecom.simulator.codec.SimulatorCodec;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;

/**
 * DiameterServerInitializer — Sets up the Netty pipeline for each new TCP connection.
 *
 * WHAT IS A PIPELINE?
 * A Netty pipeline is a chain of handlers. Each handler processes data
 * and passes it to the next handler. Think of it like an assembly line:
 *
 *   [Raw bytes from TCP]
 *        ↓
 *   [FrameDecoder]         → Cuts stream into complete messages
 *        ↓
 *   [MessageDecoder]       → bytes → DiameterMessage Java object
 *        ↓
 *   [IdleStateHandler]     → Detects if client stopped sending
 *        ↓
 *   [ServerHandler]        → Business logic: CER/DWR/CCR
 *        ↓
 *   [MessageEncoder]       → DiameterMessage Java object → bytes
 *        ↓
 *   [Raw bytes to TCP]
 *
 * WHY DO WE NEED A FRAME DECODER?
 *
 * TCP is a "stream" protocol, not a "message" protocol.
 * When you send 2 Diameter messages back-to-back, TCP might deliver them as:
 *   - Two separate reads (perfect case, rarely happens)
 *   - One combined read (both messages in one chunk)
 *   - Three reads (first half of msg1, then rest of msg1 + all of msg2, etc.)
 *
 * This is called "TCP fragmentation" or "TCP framing problem".
 *
 * SOLUTION: LengthFieldBasedFrameDecoder
 * Diameter header tells us the total message length at bytes 1-3.
 * The frame decoder reads this length field and buffers bytes until a
 * COMPLETE message is assembled, then passes it downstream.
 *
 * LengthFieldBasedFrameDecoder constructor parameters:
 *   maxFrameLength: 65535    → max Diameter message size (64KB limit)
 *   lengthFieldOffset: 1     → length field starts at byte 1 (after version byte)
 *   lengthFieldLength: 3     → length field is 3 bytes wide
 *   lengthAdjustment: -4     → adjustment: the length field VALUE includes bytes
 *                              before it (version=1 + length=3 = 4 bytes already counted)
 *                              so we subtract 4 to not double-count them
 *   initialBytesToStrip: 0   → don't strip anything, pass full frame including header
 */
public class DiameterServerInitializer extends ChannelInitializer<SocketChannel> {

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();

        // ─── INBOUND handlers (process data COMING IN from client) ────

        // Step 1: Frame decoder — assembles complete Diameter messages from TCP stream
        // Without this, we might receive partial messages and crash on decode
        pipeline.addLast("frameDecoder",
            new LengthFieldBasedFrameDecoder(
                65535,  // maxFrameLength: reject messages larger than 64KB
                1,      // lengthFieldOffset: skip version byte, length starts at byte 1
                3,      // lengthFieldLength: length field is 3 bytes
                -4,     // lengthAdjustment: length value includes the 4 bytes before it
                0       // initialBytesToStrip: keep the full frame, don't strip header
            )
        );

        // Step 2: Diameter message decoder — bytes → DiameterMessage
        pipeline.addLast("messageDecoder", new DiameterMessageDecoder());

        // Step 3: Idle state detector — fires event if no traffic for 60s
        // Used to detect dead connections (client crashed without closing TCP)
        pipeline.addLast("idleHandler",
            new IdleStateHandler(60, 0, 0, TimeUnit.SECONDS)
            // 60s reader idle → readerIdleEvent → close connection
        );

        // Step 4: Business logic handler — routes messages to CER/DWR/CCR handlers
        pipeline.addLast("serverHandler", new DiameterServerHandler(new SimulatorCodec()));

        // ─── OUTBOUND handlers (process data GOING OUT to client) ────

        // Step 5: Diameter message encoder — DiameterMessage → bytes
        // (outbound handlers run in reverse order from inbound)
        pipeline.addLast("messageEncoder", new DiameterMessageEncoder());
    }
}
