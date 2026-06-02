package com.telecom.simulator.handler;

import com.telecom.simulator.model.DiameterMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * DiameterMessageEncoder — Converts DiameterMessage into raw bytes for sending.
 *
 * This is a Netty MessageToByteEncoder:
 * - Input:  DiameterMessage (what our handler writes to the channel)
 * - Output: ByteBuf (raw bytes sent over TCP)
 *
 * FLOW:
 *   DiameterMessage (from handler)
 *       → codec.encode(message)
 *       → byte[]
 *       → ByteBuf
 *       → TCP wire
 */
public class DiameterMessageEncoder extends MessageToByteEncoder<DiameterMessage> {

    private final com.telecom.simulator.codec.SimulatorCodec codec
        = new com.telecom.simulator.codec.SimulatorCodec();

    @Override
    protected void encode(ChannelHandlerContext ctx, DiameterMessage msg, ByteBuf out)
            throws Exception {
        byte[] bytes = codec.encode(msg);
        out.writeBytes(bytes);
    }
}
