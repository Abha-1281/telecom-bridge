package com.telecom.gateway.diameter.handler;

import com.telecom.gateway.diameter.codec.DiameterCodec;
import com.telecom.gateway.model.diameter.DiameterMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

/** Decodes incoming ByteBuf (from FrameDecoder) into DiameterMessage. */
public class ClientMessageDecoder extends MessageToMessageDecoder<ByteBuf> {

    private final DiameterCodec codec;

    public ClientMessageDecoder(DiameterCodec codec) {
        this.codec = codec;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        byte[] bytes = new byte[in.readableBytes()];
        in.readBytes(bytes);
        out.add(codec.decode(bytes));
    }
}
