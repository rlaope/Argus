package io.argus.aggregator.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;

/**
 * Shared HTTP response writers for the aggregator's Netty controllers. Keeps the
 * keep-alive handling and hand-built JSON error envelope in one place so
 * {@link FleetController} and {@link ProfileController} stay consistent.
 */
final class HttpResponses {

    private HttpResponses() {}

    static void sendJson(ChannelHandlerContext ctx, FullHttpRequest request,
                         HttpResponseStatus status, String json) {
        sendText(ctx, request, status, json, "application/json");
    }

    static void sendPlain(ChannelHandlerContext ctx, FullHttpRequest request,
                          HttpResponseStatus status, String content) {
        sendText(ctx, request, status, content, "text/plain; charset=utf-8");
    }

    static void sendText(ChannelHandlerContext ctx, FullHttpRequest request,
                         HttpResponseStatus status, String content, String contentType) {
        ByteBuf buf = Unpooled.copiedBuffer(content, CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, buf);
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, contentType)
                .setInt(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
        ChannelFuture future = ctx.writeAndFlush(response);
        if (!HttpUtil.isKeepAlive(request)) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    static void sendNoContent(ChannelHandlerContext ctx, FullHttpRequest request) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT, Unpooled.EMPTY_BUFFER);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        ChannelFuture future = ctx.writeAndFlush(response);
        if (!HttpUtil.isKeepAlive(request)) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    static void sendError(ChannelHandlerContext ctx, FullHttpRequest request,
                          int code, String message) {
        HttpResponseStatus status = HttpResponseStatus.valueOf(code);
        sendJson(ctx, request, status, JsonWriter.error(code, message));
    }
}
