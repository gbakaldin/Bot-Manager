package com.vingame.bot.infrastructure.client.stub;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A loopback HTTP/2 gwms gateway that advertises a <b>low {@code SETTINGS_MAX_CONCURRENT_STREAMS}</b>
 * and answers every request only after a fixed delay, so a burst of requests overlaps on one
 * connection (GATEWAY_REQUEST_BUDGET A33, staging anomaly A1).
 * <p>
 * It exists because the defect it reproduces lives in the JDK client and cannot be seen through
 * {@link StubGateway}, which is {@code com.sun.net.httpserver} and therefore HTTP/1.1 only: one
 * connection per in-flight request, no stream limit to hit.
 * <p>
 * Cleartext HTTP/2 via the {@code Upgrade: h2c} handshake, which is what the JDK client does for
 * an {@code http://} URI at its default version. <b>The handshake only happens on a request
 * without a body</b>, and requests issued before the upgraded connection is pooled each open a
 * connection of their own — so a test must warm the connection with one GET before its burst.
 * <p>
 * Loopback only, ephemeral port, nothing here can reach a real host.
 */
public final class H2cStreamLimitStub implements AutoCloseable {

    private static final String BALANCE = "{\"status\":\"OK\",\"code\":200,\"data\":[{\"main_balance\":1000000}]}";
    private static final String TOKENS = "{\"status\":\"OK\",\"code\":200,\"message\":\"OK\",\"data\":[{"
            + "\"session_id\":\"session-1\",\"token\":\"18-agency-1\",\"token2\":\"jwt-1\"}]}";

    private final EventLoopGroup group;
    private final Channel server;
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger maxActive = new AtomicInteger();
    private final AtomicInteger answered = new AtomicInteger();

    private H2cStreamLimitStub(int maxConcurrentStreams, Duration delay) throws InterruptedException {
        this.group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        HttpServerCodec source = new HttpServerCodec();
                        HttpServerUpgradeHandler.UpgradeCodecFactory upgrades = protocol ->
                                AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)
                                        ? new Http2ServerUpgradeCodec(
                                                Http2FrameCodecBuilder.forServer()
                                                        .initialSettings(Http2Settings.defaultSettings()
                                                                .maxConcurrentStreams(maxConcurrentStreams))
                                                        .build(),
                                                new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                                                    @Override
                                                    protected void initChannel(Channel stream) {
                                                        stream.pipeline().addLast(new StreamHandler(delay));
                                                    }
                                                }))
                                        : null;
                        ch.pipeline().addLast(source, new HttpServerUpgradeHandler(source, upgrades));
                    }
                });
        this.server = bootstrap.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).sync().channel();
    }

    /** Start on an ephemeral loopback port. */
    public static H2cStreamLimitStub start(int maxConcurrentStreams, Duration delay) throws InterruptedException {
        return new H2cStreamLimitStub(maxConcurrentStreams, delay);
    }

    public String baseUrl() {
        InetSocketAddress address = (InetSocketAddress) server.localAddress();
        return "http://127.0.0.1:" + address.getPort();
    }

    /** The most streams this server ever had open at once — the independent witness. */
    public int maxConcurrentStreamsObserved() {
        return maxActive.get();
    }

    public int answered() {
        return answered.get();
    }

    @Override
    public void close() {
        server.close().syncUninterruptibly();
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    /** One per HTTP/2 stream: answer once the request has fully arrived, after the delay. */
    private final class StreamHandler extends ChannelInboundHandlerAdapter {
        private final Duration delay;
        private String path = "";
        private boolean counted;

        StreamHandler(Duration delay) {
            this.delay = delay;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                boolean end = false;
                if (msg instanceof Http2HeadersFrame headers) {
                    CharSequence p = headers.headers().path();
                    path = p == null ? "" : p.toString();
                    open();
                    end = headers.isEndStream();
                } else if (msg instanceof Http2DataFrame data) {
                    end = data.isEndStream();
                }
                if (end) {
                    ctx.executor().schedule(() -> respond(ctx), delay.toMillis(), TimeUnit.MILLISECONDS);
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        private void open() {
            if (!counted) {
                counted = true;
                maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            }
        }

        private void respond(ChannelHandlerContext ctx) {
            String body = path.contains("verifytoken") ? BALANCE : TOKENS;
            active.decrementAndGet();
            answered.incrementAndGet();
            ctx.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()
                    .status("200").set("content-type", "application/json")));
            ctx.writeAndFlush(new DefaultHttp2DataFrame(
                    Unpooled.copiedBuffer(body, StandardCharsets.UTF_8), true));
        }
    }
}
