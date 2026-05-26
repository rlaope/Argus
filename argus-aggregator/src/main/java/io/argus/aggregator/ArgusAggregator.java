package io.argus.aggregator;

import io.argus.aggregator.alert.FleetAlertEvaluator;
import io.argus.aggregator.http.AggregatorChannelHandler;
import io.argus.aggregator.http.FleetController;
import io.argus.aggregator.http.PrometheusMetricsExporter;
import io.argus.aggregator.scrape.RemoteWriteSink;
import io.argus.aggregator.scrape.ScrapeLoop;
import io.argus.aggregator.store.FleetRegistry;
import io.argus.core.alert.AlertRule;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Argus Aggregator — long-running Netty server that pulls metrics from a fleet
 * of argus-agent instances on K8s and exposes them via REST + Prometheus.
 *
 * <p>This is the entry point for the {@code argus-aggregator} module.
 *
 * <p>Flags / system properties (CLI overrides system properties):
 * <ul>
 *   <li>{@code --port=N} / {@code -Dargus.aggregator.port=N} — bind port (default 9300)</li>
 *   <li>{@code --bind=ADDR} / {@code -Dargus.aggregator.bind=ADDR} — bind address (default 0.0.0.0)</li>
 *   <li>{@code --scrape-interval=N} / {@code -Dargus.aggregator.scrape.interval.seconds=N} — scrape interval, default 5s</li>
 *   <li>{@code --retention=N} / {@code -Dargus.aggregator.retention.seconds=N} — ring buffer retention, default 3600s</li>
 *   <li>{@code --remote-write=URL} / {@code -Dargus.aggregator.remote.write.url=URL} — optional remote_write sink</li>
 * </ul>
 */
public final class ArgusAggregator {

    private static final System.Logger LOG = System.getLogger(ArgusAggregator.class.getName());

    /**
     * Canonical default port for argus-aggregator.
     *
     * <p>SECURITY: the server binds {@code 0.0.0.0} by default so it can be
     * reached from inside a K8s cluster. All endpoints are unauthenticated
     * on day-1 — production deployments MUST sit behind a {@code ClusterIP}
     * Service plus a NetworkPolicy that restricts ingress to the operator
     * and frontend service accounts. The {@code /fleet/targets} POST/DELETE
     * endpoints accept arbitrary scrape targets — exposure to untrusted
     * callers is an SSRF risk. See {@code docs/aggregator-api.md}'s
     * "Security warning" section.
     *
     * <p>If this value changes, also update:
     * <ul>
     *   <li>{@code charts/argus/values.yaml} → {@code operator.aggregatorUrl}</li>
     *   <li>{@code argus-operator/.../ArgusOperator.DEFAULT_AGGREGATOR_URL}</li>
     * </ul>
     */
    private static final int DEFAULT_PORT = 9300;
    private static final long DEFAULT_SCRAPE_INTERVAL_SECONDS = 5;
    private static final long DEFAULT_RETENTION_SECONDS = 3600;

    private final Config config;
    private final FleetRegistry registry;
    private final ScrapeLoop scrapeLoop;
    private final FleetAlertEvaluator alertEvaluator;
    private final RemoteWriteSink remoteWrite;
    private final PrometheusMetricsExporter prometheus;
    private final FleetController controller;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public ArgusAggregator(Config config) {
        this(config, List.of());
    }

    public ArgusAggregator(Config config, List<AlertRule> rules) {
        this.config = config;
        this.registry = new FleetRegistry(config.retentionSeconds);
        this.remoteWrite = new RemoteWriteSink(config.remoteWriteUrl);
        this.alertEvaluator = new FleetAlertEvaluator(registry, rules);
        this.scrapeLoop = new ScrapeLoop(registry, config.scrapeIntervalSeconds, result -> {
            if (result.ok() && result.metrics() != null) {
                alertEvaluator.evaluate(result.podId(), result.rawMetrics());
                remoteWrite.offer(registry.get(result.podId()), result.metrics());
            }
        });
        this.prometheus = new PrometheusMetricsExporter(registry, scrapeLoop);
        this.controller = new FleetController(registry, prometheus);
    }

    public FleetRegistry registry() { return registry; }
    public ScrapeLoop scrapeLoop() { return scrapeLoop; }
    public FleetAlertEvaluator alertEvaluator() { return alertEvaluator; }

    public void start() throws InterruptedException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("aggregator already running");
        }
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(1024 * 1024))
                                .addLast(new AggregatorChannelHandler(controller));
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

        serverChannel = bootstrap.bind(config.bindAddress, config.port).sync().channel();

        scrapeLoop.start();
        remoteWrite.start();

        LOG.log(System.Logger.Level.INFO, "argus-aggregator listening on "
                + config.bindAddress + ":" + config.port);
        LOG.log(System.Logger.Level.INFO, "scrape interval = " + config.scrapeIntervalSeconds + "s, "
                + "retention = " + config.retentionSeconds + "s, "
                + "remote_write = " + (remoteWrite.isEnabled() ? config.remoteWriteUrl : "disabled"));
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        scrapeLoop.stop();
        remoteWrite.stop();
        if (serverChannel != null) serverChannel.close();
        if (bossGroup != null) bossGroup.shutdownGracefully();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        LOG.log(System.Logger.Level.INFO, "argus-aggregator stopped");
    }

    public boolean isRunning() {
        return running.get();
    }

    public int port() { return config.port; }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        ArgusAggregator aggregator = new ArgusAggregator(config);
        aggregator.start();
        Runtime.getRuntime().addShutdownHook(new Thread(aggregator::stop, "argus-aggregator-shutdown"));
        Thread.currentThread().join();
    }

    /** Runtime configuration. */
    public static final class Config {
        public final int port;
        public final String bindAddress;
        public final long scrapeIntervalSeconds;
        public final long retentionSeconds;
        public final String remoteWriteUrl;

        public Config(int port, String bindAddress, long scrapeIntervalSeconds,
                      long retentionSeconds, String remoteWriteUrl) {
            this.port = port;
            this.bindAddress = bindAddress;
            this.scrapeIntervalSeconds = scrapeIntervalSeconds;
            this.retentionSeconds = retentionSeconds;
            this.remoteWriteUrl = remoteWriteUrl;
        }

        public static Config parse(String[] args) {
            int port = Integer.getInteger("argus.aggregator.port", DEFAULT_PORT);
            String bind = System.getProperty("argus.aggregator.bind", "0.0.0.0");
            long interval = Long.getLong("argus.aggregator.scrape.interval.seconds",
                    DEFAULT_SCRAPE_INTERVAL_SECONDS);
            long retention = Long.getLong("argus.aggregator.retention.seconds",
                    DEFAULT_RETENTION_SECONDS);
            String remoteWrite = System.getProperty("argus.aggregator.remote.write.url", "");

            List<String> extra = new ArrayList<>();
            for (String arg : args) {
                if (arg.startsWith("--port=")) {
                    port = Integer.parseInt(arg.substring("--port=".length()));
                } else if (arg.startsWith("--bind=")) {
                    bind = arg.substring("--bind=".length());
                } else if (arg.startsWith("--scrape-interval=")) {
                    interval = Long.parseLong(arg.substring("--scrape-interval=".length()));
                } else if (arg.startsWith("--retention=")) {
                    retention = Long.parseLong(arg.substring("--retention=".length()));
                } else if (arg.startsWith("--remote-write=")) {
                    remoteWrite = arg.substring("--remote-write=".length());
                } else {
                    extra.add(arg);
                }
            }
            if (!extra.isEmpty()) {
                System.err.println("[argus-aggregator] ignoring unknown args: " + extra);
            }
            return new Config(port, bind, interval, retention, remoteWrite);
        }
    }
}
