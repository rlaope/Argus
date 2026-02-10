package io.argus.server.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

/**
 * Helper class for sending HTTP responses.
 *
 * <p>Provides utility methods for sending various types of HTTP responses
 * with proper content type and connection handling.
 */
public final class HttpResponseHelper {

    private HttpResponseHelper() {
        // Utility class
    }

    /**
     * Sends a text HTTP response.
     *
     * @param ctx         the channel handler context
     * @param request     the original HTTP request
     * @param status      the response status
     * @param content     the response body
     * @param contentType the content type
     */
    public static void send(ChannelHandlerContext ctx, FullHttpRequest request,
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

    /**
     * Sends a JSON HTTP response.
     *
     * @param ctx     the channel handler context
     * @param request the original HTTP request
     * @param json    the JSON response body
     */
    public static void sendJson(ChannelHandlerContext ctx, FullHttpRequest request, String json) {
        send(ctx, request, HttpResponseStatus.OK, json, "application/json");
    }

    /**
     * Sends a 404 Not Found response.
     *
     * @param ctx     the channel handler context
     * @param request the original HTTP request
     */
    public static void sendNotFound(ChannelHandlerContext ctx, FullHttpRequest request) {
        send(ctx, request, HttpResponseStatus.NOT_FOUND, "Not Found", "text/plain");
    }

    /**
     * Sends a 400 Bad Request response.
     *
     * @param ctx     the channel handler context
     * @param request the original HTTP request
     * @param message the error message
     */
    public static void sendBadRequest(ChannelHandlerContext ctx, FullHttpRequest request, String message) {
        send(ctx, request, HttpResponseStatus.BAD_REQUEST, message, "text/plain");
    }

    /**
     * Sends a Prometheus exposition format response.
     *
     * @param ctx     the channel handler context
     * @param request the original HTTP request
     * @param content the Prometheus text format content
     */
    public static void sendPlainText(ChannelHandlerContext ctx, FullHttpRequest request, String content) {
        send(ctx, request, HttpResponseStatus.OK, content, "text/plain; charset=utf-8");
    }

    public static void sendPrometheus(ChannelHandlerContext ctx, FullHttpRequest request, String content) {
        send(ctx, request, HttpResponseStatus.OK, content, "text/plain; version=0.0.4; charset=utf-8");
    }

    /**
     * Sends a downloadable file response.
     *
     * @param ctx         the channel handler context
     * @param request     the original HTTP request
     * @param content     the file content
     * @param contentType the content type
     * @param filename    the suggested filename
     */
    public static void sendDownload(ChannelHandlerContext ctx, FullHttpRequest request,
                                    String content, String contentType, String filename) {
        ByteBuf buf = Unpooled.copiedBuffer(content, CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buf);
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, contentType)
                .setInt(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes())
                .set(HttpHeaderNames.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");

        ChannelFuture future = ctx.writeAndFlush(response);
        if (!HttpUtil.isKeepAlive(request)) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }
}
