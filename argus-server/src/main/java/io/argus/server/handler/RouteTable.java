package io.argus.server.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Lightweight HTTP route table for Netty handlers.
 * Replaces long if/else chains with a declarative route registration.
 *
 * <p>Supports exact path matching and prefix matching:
 * <pre>
 * RouteTable routes = RouteTable.builder()
 *     .exact("/health", this::handleHealth)
 *     .prefix("/api/", this::handleApi)
 *     .build();
 *
 * routes.dispatch(ctx, request, uri);
 * </pre>
 */
public final class RouteTable {

    @FunctionalInterface
    public interface RouteHandler {
        void handle(ChannelHandlerContext ctx, FullHttpRequest request, String uri);
    }

    private final Map<String, RouteHandler> exactRoutes;
    private final List<PrefixRoute> prefixRoutes;

    private RouteTable(Map<String, RouteHandler> exactRoutes, List<PrefixRoute> prefixRoutes) {
        this.exactRoutes = exactRoutes;
        this.prefixRoutes = prefixRoutes;
    }

    /**
     * Try to dispatch the request. Returns true if a route matched.
     */
    public boolean dispatch(ChannelHandlerContext ctx, FullHttpRequest request, String uri) {
        // Exact match first
        RouteHandler handler = exactRoutes.get(uri);
        if (handler != null) {
            handler.handle(ctx, request, uri);
            return true;
        }

        // Prefix match (first wins)
        for (PrefixRoute route : prefixRoutes) {
            if (uri.startsWith(route.prefix)) {
                route.handler.handle(ctx, request, uri);
                return true;
            }
        }

        return false;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, RouteHandler> exact = new LinkedHashMap<>();
        private final List<PrefixRoute> prefix = new ArrayList<>();

        public Builder exact(String path, RouteHandler handler) {
            exact.put(path, handler);
            return this;
        }

        public Builder prefix(String pathPrefix, RouteHandler handler) {
            prefix.add(new PrefixRoute(pathPrefix, handler));
            return this;
        }

        public RouteTable build() {
            return new RouteTable(Map.copyOf(exact), List.copyOf(prefix));
        }
    }

    private record PrefixRoute(String prefix, RouteHandler handler) {}
}
