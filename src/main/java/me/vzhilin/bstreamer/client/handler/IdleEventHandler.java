package me.vzhilin.bstreamer.client.handler;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import me.vzhilin.bstreamer.client.ClientAttributes;
import me.vzhilin.bstreamer.client.ConnectionStatistics;

@ChannelHandler.Sharable
final class IdleEventHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            ConnectionStatistics connectionStat = ctx.channel().attr(ClientAttributes.STAT).get();
            connectionStat.onIdleError();
            ctx.close();
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }
}
