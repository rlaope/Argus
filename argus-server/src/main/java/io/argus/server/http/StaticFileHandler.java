package io.argus.server.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;

import java.io.InputStream;

/**
 * Handles serving static files from classpath resources.
 *
 * <p>Serves HTML, CSS, and JavaScript files from the public/ directory
 * in the classpath for the dashboard UI.
 */
public final class StaticFileHandler {

    private static final System.Logger LOG = System.getLogger(StaticFileHandler.class.getName());

    /**
     * Attempts to serve a static file for the given URI.
     *
     * @param ctx     the channel handler context
     * @param request the HTTP request
     * @param uri     the request URI
     * @return true if the file was served, false if not found
     */
    public boolean serve(ChannelHandlerContext ctx, FullHttpRequest request, String uri) {
        String resourcePath = mapUriToResource(uri);
        if (resourcePath == null) {
            return false;
        }

        String contentType = getContentType(uri);

        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                return false;
            }

            byte[] bytes = is.readAllBytes();
            ByteBuf buf = Unpooled.wrappedBuffer(bytes);

            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buf);
            response.headers()
                    .set(HttpHeaderNames.CONTENT_TYPE, contentType)
                    .setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length)
                    .set(HttpHeaderNames.CACHE_CONTROL, "no-cache");

            ChannelFuture future = ctx.writeAndFlush(response);
            if (!HttpUtil.isKeepAlive(request)) {
                future.addListener(ChannelFutureListener.CLOSE);
            }
            return true;

        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Error serving static file {0}: {1}", uri, e.getMessage());
            return false;
        }
    }

    /**
     * Maps a URI to a classpath resource path.
     *
     * @param uri the request URI
     * @return resource path or null if not a static file
     */
    private String mapUriToResource(String uri) {
        if ("/".equals(uri) || "/index.html".equals(uri)) {
            return "public/index.html";
        }
        if (uri.startsWith("/css/") && uri.endsWith(".css")) {
            return "public" + uri;
        }
        if (uri.startsWith("/js/") && uri.endsWith(".js")) {
            return "public" + uri;
        }
        return null;
    }

    /**
     * Returns the content type for a URI.
     *
     * @param uri the request URI
     * @return MIME type string
     */
    private String getContentType(String uri) {
        if (uri.endsWith(".html") || "/".equals(uri)) {
            return "text/html; charset=UTF-8";
        }
        if (uri.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        }
        if (uri.endsWith(".js")) {
            return "application/javascript; charset=UTF-8";
        }
        return "application/octet-stream";
    }
}
