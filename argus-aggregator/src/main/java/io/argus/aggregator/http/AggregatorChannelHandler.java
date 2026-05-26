package io.argus.aggregator.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;

/**
 * Channel handler that delegates each incoming HTTP request to a
 * {@link FleetController}. Falls back to 404 when no route matches.
 */
public final class AggregatorChannelHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final System.Logger LOG = System.getLogger(AggregatorChannelHandler.class.getName());

    private final FleetController controller;

    public AggregatorChannelHandler(FleetController controller) {
        this.controller = controller;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!controller.dispatch(ctx, request)) {
            sendNotFound(ctx, request);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.log(System.Logger.Level.WARNING, "channel exception", cause);
        ctx.close();
    }

    private static void sendNotFound(ChannelHandlerContext ctx, FullHttpRequest request) {
        String body = JsonWriter.error(404, "not found");
        ByteBuf buf = Unpooled.copiedBuffer(body, CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND, buf);
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "application/json")
                .setInt(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
        ChannelFuture future = ctx.writeAndFlush(response);
        if (!HttpUtil.isKeepAlive(request)) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }
}
