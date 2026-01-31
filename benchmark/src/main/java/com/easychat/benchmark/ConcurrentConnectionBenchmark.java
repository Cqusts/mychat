package com.easychat.benchmark;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.*;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并发WebSocket长连接压测
 *
 * 测试目标: 验证单机能否支撑 5000+ 并发长连接
 *
 * 原理:
 * 1. 从 tokens.txt 读取预生成的token
 * 2. 按 ramp-up 速率逐步建立WebSocket连接
 * 3. 每个连接定期发送心跳保持活跃
 * 4. 统计成功连接数、失败数、连接耗时
 * 5. 保持连接一段时间后统计存活率
 */
public class ConcurrentConnectionBenchmark {

    static final AtomicInteger connectedCount = new AtomicInteger(0);
    static final AtomicInteger failedCount = new AtomicInteger(0);
    static final AtomicInteger disconnectedCount = new AtomicInteger(0);

    public static void run(String[] args) throws Exception {
        ArgParser p = new ArgParser(args);
        String wsUrl = p.get("ws-url", "ws://localhost:5051/ws");
        String tokensFile = p.get("tokens-file", "tokens.txt");
        int targetConnections = p.getInt("connections", 5000);
        int rampUp = p.getInt("ramp-up", 200);
        int holdSeconds = p.getInt("hold-seconds", 60);
        int heartbeatInterval = p.getInt("heartbeat-interval", 30);

        // 读取tokens
        List<String> tokens = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(tokensFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) tokens.add(line);
            }
        }

        if (tokens.size() < targetConnections) {
            System.out.printf("警告: tokens文件中只有 %d 个token, 目标连接数 %d, 将使用可用的token数%n",
                tokens.size(), targetConnections);
            targetConnections = tokens.size();
        }

        System.out.println("========================================");
        System.out.println("  EasyChat 并发连接压测");
        System.out.println("========================================");
        System.out.printf("  目标连接数:    %d%n", targetConnections);
        System.out.printf("  Ramp-up速率:  %d 连接/秒%n", rampUp);
        System.out.printf("  保持时间:      %d 秒%n", holdSeconds);
        System.out.printf("  心跳间隔:      %d 秒%n", heartbeatInterval);
        System.out.printf("  WebSocket URL: %s%n", wsUrl);
        System.out.println("========================================");

        URI uri = new URI(wsUrl);
        String host = uri.getHost();
        int port = uri.getPort() == -1 ? 80 : uri.getPort();

        // Netty客户端线程组 - 使用较多线程处理大量连接
        int workerThreads = Math.min(Runtime.getRuntime().availableProcessors() * 2, 16);
        EventLoopGroup group = new NioEventLoopGroup(workerThreads);
        List<Channel> channels = new ArrayList<>();

        // 心跳调度器
        ScheduledExecutorService heartbeatScheduler = new ScheduledThreadPoolExecutor(4);

        // 进度报告
        ScheduledExecutorService reporter = new ScheduledThreadPoolExecutor(1);
        reporter.scheduleAtFixedRate(() -> {
            System.out.printf("[进度] 已连接: %d | 失败: %d | 已断开: %d | 存活: %d%n",
                connectedCount.get(), failedCount.get(), disconnectedCount.get(),
                connectedCount.get() - disconnectedCount.get());
        }, 2, 2, TimeUnit.SECONDS);

        long startTime = System.currentTimeMillis();

        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);

            // 按 ramp-up 速率逐步建立连接
            int batchSize = rampUp;
            for (int i = 0; i < targetConnections; i += batchSize) {
                int batchEnd = Math.min(i + batchSize, targetConnections);
                CountDownLatch batchLatch = new CountDownLatch(batchEnd - i);

                for (int j = i; j < batchEnd; j++) {
                    String token = tokens.get(j);
                    String fullUrl = wsUrl + "?token=" + token;

                    WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                        new URI(fullUrl), WebSocketVersion.V13, null, true, new DefaultHttpHeaders());

                    final int connIdx = j;
                    bootstrap.handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                new HttpClientCodec(),
                                new HttpObjectAggregator(65536),
                                new BenchmarkWebSocketHandler(handshaker, connIdx, batchLatch,
                                    heartbeatScheduler, heartbeatInterval)
                            );
                        }
                    });

                    bootstrap.connect(host, port).addListener((ChannelFutureListener) future -> {
                        if (future.isSuccess()) {
                            synchronized (channels) {
                                channels.add(future.channel());
                            }
                        } else {
                            failedCount.incrementAndGet();
                            batchLatch.countDown();
                        }
                    });
                }

                // 等待这批连接完成(最多10秒)，然后继续下一批
                batchLatch.await(10, TimeUnit.SECONDS);

                // 如果还没建完所有连接，等1秒再建下一批(实现ramp-up)
                if (batchEnd < targetConnections) {
                    Thread.sleep(1000);
                }
            }

            long connectTime = System.currentTimeMillis() - startTime;

            System.out.println();
            System.out.println("========== 连接建立阶段完成 ==========");
            System.out.printf("  总耗时:      %.1f 秒%n", connectTime / 1000.0);
            System.out.printf("  成功连接:    %d%n", connectedCount.get());
            System.out.printf("  失败连接:    %d%n", failedCount.get());
            System.out.printf("  成功率:      %.1f%%%n",
                connectedCount.get() * 100.0 / targetConnections);
            System.out.println("======================================");
            System.out.printf("%n保持连接 %d 秒，监测连接存活率...%n%n", holdSeconds);

            // 保持连接阶段
            Thread.sleep(holdSeconds * 1000L);

            int aliveCount = connectedCount.get() - disconnectedCount.get();

            System.out.println();
            System.out.println("============ 最终测试结果 ============");
            System.out.printf("  目标连接数:   %d%n", targetConnections);
            System.out.printf("  成功建立:     %d%n", connectedCount.get());
            System.out.printf("  连接失败:     %d%n", failedCount.get());
            System.out.printf("  保持期断开:   %d%n", disconnectedCount.get());
            System.out.printf("  最终存活:     %d%n", aliveCount);
            System.out.printf("  存活率:       %.1f%%%n", aliveCount * 100.0 / targetConnections);
            System.out.printf("  连接建立耗时: %.1f 秒%n", connectTime / 1000.0);
            System.out.println("======================================");

            if (aliveCount >= 5000) {
                System.out.println(">>> 结论: 单机支撑 5000+ 并发长连接 -- 通过");
            } else if (aliveCount >= targetConnections * 0.95) {
                System.out.printf(">>> 结论: 单机支撑 %d 并发长连接(存活率>95%%) -- 基本通过%n", aliveCount);
            } else {
                System.out.printf(">>> 结论: 最终存活 %d 连接, 未达到目标 -- 需优化%n", aliveCount);
            }

        } finally {
            reporter.shutdown();
            heartbeatScheduler.shutdown();
            // 关闭所有连接
            System.out.println("正在关闭所有连接...");
            synchronized (channels) {
                for (Channel ch : channels) {
                    if (ch.isActive()) ch.close();
                }
            }
            group.shutdownGracefully().sync();
            System.out.println("测试结束。");
        }
    }

    /**
     * WebSocket客户端Handler, 处理握手、心跳、断线计数
     */
    static class BenchmarkWebSocketHandler extends SimpleChannelInboundHandler<Object> {
        private final WebSocketClientHandshaker handshaker;
        private final int index;
        private final CountDownLatch latch;
        private final ScheduledExecutorService heartbeatScheduler;
        private final int heartbeatInterval;

        BenchmarkWebSocketHandler(WebSocketClientHandshaker handshaker, int index,
                                  CountDownLatch latch, ScheduledExecutorService heartbeatScheduler,
                                  int heartbeatInterval) {
            this.handshaker = handshaker;
            this.index = index;
            this.latch = latch;
            this.heartbeatScheduler = heartbeatScheduler;
            this.heartbeatInterval = heartbeatInterval;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            handshaker.handshake(ctx.channel());
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (!handshaker.isHandshakeComplete()) {
                try {
                    handshaker.finishHandshake(ctx.channel(), (io.netty.handler.codec.http.FullHttpResponse) msg);
                    connectedCount.incrementAndGet();
                    latch.countDown();

                    // 启动心跳 - 发送任意文本即可触发服务端saveUserHeartBeat
                    Channel ch = ctx.channel();
                    heartbeatScheduler.scheduleAtFixedRate(() -> {
                        if (ch.isActive()) {
                            ch.writeAndFlush(new TextWebSocketFrame("heartbeat"));
                        }
                    }, heartbeatInterval, heartbeatInterval, TimeUnit.SECONDS);
                } catch (Exception e) {
                    failedCount.incrementAndGet();
                    latch.countDown();
                }
                return;
            }

            if (msg instanceof TextWebSocketFrame) {
                // 收到服务端推送消息，忽略即可
            } else if (msg instanceof CloseWebSocketFrame) {
                ctx.close();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            disconnectedCount.incrementAndGet();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (!handshaker.isHandshakeComplete()) {
                failedCount.incrementAndGet();
                latch.countDown();
            }
            ctx.close();
        }
    }
}
