package com.telecom.simulator.handler;

import com.telecom.simulator.codec.SimulatorCodec;
import com.telecom.simulator.model.DiameterMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

/**
 * DiameterMessageDecoder — Converts raw Netty ByteBuf into DiameterMessage.
 *
 * This is a Netty MessageToMessageDecoder:
 * - Input:  ByteBuf (raw bytes from the frame decoder)
 * - Output: DiameterMessage (Java object ready for business logic)
 *
 * By the time this decoder receives data, the FrameDecoder has already
 * ensured we have a COMPLETE Diameter message (no partial messages).
 *
 * FLOW:
 *   ByteBuf (complete Diameter frame)
 *       → extract to byte[]
 *       → codec.decode(bytes)
 *       → DiameterMessage
 *       → passed to next handler (DiameterServerHandler)
 */
public class DiameterMessageDecoder extends MessageToMessageDecoder<ByteBuf> {

    private final SimulatorCodec codec = new SimulatorCodec();

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out)
            throws Exception {
        // Convert ByteBuf to byte array
        byte[] bytes = new byte[in.readableBytes()];
        in.readBytes(bytes);

        // Decode binary → Java object
        DiameterMessage message = codec.decode(bytes);

        // Pass to next handler in the pipeline
        out.add(message);
    }
}
