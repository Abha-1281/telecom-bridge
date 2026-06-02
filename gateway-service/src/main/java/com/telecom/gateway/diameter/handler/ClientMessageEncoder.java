package com.telecom.gateway.diameter.handler;

import com.telecom.gateway.diameter.codec.DiameterCodec;
import com.telecom.gateway.model.diameter.DiameterMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/** Encodes outgoing DiameterMessage into raw bytes for TCP transmission. */
public class ClientMessageEncoder extends MessageToByteEncoder<DiameterMessage> {

    private final DiameterCodec codec;

    public ClientMessageEncoder(DiameterCodec codec) {
        this.codec = codec;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, DiameterMessage msg, ByteBuf out) {
        byte[] bytes = codec.encode(msg);
        out.writeBytes(bytes);
    }
}
