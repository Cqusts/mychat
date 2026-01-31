package com.easychat.benchmark;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.*;
import org.HdrHistogram.Histogram;
import org.redisson.Redisson;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 端到端消息延迟测试
 *
 * 测试原理:
 * 1. 连接两个WebSocket客户端(发送端A + 接收端B)
 * 2. 通过Redisson RTopic发布消息(模拟服务端sendMessage)，消息体携带发送时间戳
 * 3. 接收端B收到WebSocket推送后计算 (收到时间 - 发送时间戳) = 端到端延迟
 * 4. 使用HdrHistogram统计 P50/P90/P95/P99/Max
 *
 * 这测量的完整链路:
 *   RTopic.publish() -> Redis广播 -> MessageHandler.lisMessage()
 *   -> channelContextUtils.sendMessage() -> Netty write -> 客户端收到
 */
public class LatencyBenchmark {

    private static final String MESSAGE_TOPIC = "message.topic";
    private static final Histogram histogram = new Histogram(TimeUnit.SECONDS.toNanos(10), 3);
    private static final Map<Long, Long> sendTimestamps = new ConcurrentHashMap<>();
    private static final AtomicLong messageIdSeq = new AtomicLong(0);
    private static final AtomicLong receivedCount = new AtomicLong(0);

    public static void run(String[] args) throws Exception {
        ArgParser p = new ArgParser(args);
        String wsUrl = p.get("ws-url", "ws://localhost:5051/ws");
        String redisUrl = p.get("redis-url", "redis://localhost:6379");
        String tokensFile = p.get("tokens-file", "tokens.txt");
        int messageCount = p.getInt("messages", 1000);
        long intervalMs = p.getLong("interval-ms", 100);

        // 读取tokens (至少需要1个)
        List<String> tokens = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(tokensFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) tokens.add(line);
            }
        }

        if (tokens.isEmpty()) {
            System.out.println("错误: tokens文件为空，请先运行 gen-tokens 生成测试token");
            return;
        }

        System.out.println("========================================");
        System.out.println("  EasyChat 端到端消息延迟测试");
        System.out.println("========================================");
        System.out.printf("  消息数量: %d%n", messageCount);
        System.out.printf("  发送间隔: %d ms%n", intervalMs);
        System.out.printf("  Redis:    %s%n", redisUrl);
        System.out.printf("  WS URL:   %s%n", wsUrl);
        System.out.println("========================================");

        URI uri = new URI(wsUrl);
        String host = uri.getHost();
        int port = uri.getPort() == -1 ? 80 : uri.getPort();

        EventLoopGroup group = new NioEventLoopGroup(2);

        // 连接接收端
        String receiverToken = tokens.get(0);
        // 从token反推userId: 在gen-tokens中userId格式为 BENCH_XXXXXX
        // 接收端的contactId就是userId
        String receiverUserId = "BENCH_000000";

        System.out.println("正在建立WebSocket接收端连接...");
        CountDownLatch connectLatch = new CountDownLatch(1);
        Channel receiverChannel = connectWebSocket(group, host, port, wsUrl, receiverToken, connectLatch,
            new LatencyMeasureHandler(connectLatch));
        if (!connectLatch.await(10, TimeUnit.SECONDS)) {
            System.out.println("错误: WebSocket连接超时");
            group.shutdownGracefully();
            return;
        }
        System.out.println("接收端已连接。");

        // 创建Redisson客户端用于发布消息
        Config config = new Config();
        config.useSingleServer().setAddress(redisUrl);
        RedissonClient redisson = Redisson.create(config);

        System.out.printf("开始发送 %d 条消息...%n%n", messageCount);
        long testStart = System.currentTimeMillis();

        RTopic topic = redisson.getTopic(MESSAGE_TOPIC);

        for (int i = 0; i < messageCount; i++) {
            long msgId = messageIdSeq.incrementAndGet();
            long sendTime = System.nanoTime();
            sendTimestamps.put(msgId, sendTime);

            // 构造 MessageSendDto，发送给接收端用户
            // contactId 以 U 开头会走 send2User 路径
            String msgJson = String.format(
                "{\"messageId\":%d,\"contactId\":\"%s\",\"sendUserId\":\"BENCH_SENDER\"," +
                "\"sendUserNickName\":\"sender\",\"messageContent\":\"latency_test_%d\"," +
                "\"messageType\":2,\"sendTime\":%d,\"contactType\":0," +
                "\"extendData\":{\"benchmarkMsgId\":%d,\"sendNanoTime\":%d}}",
                msgId, receiverUserId, i, System.currentTimeMillis(), msgId, sendTime
            );

            // 直接publish到RTopic, 走完整的消息广播链路
            topic.publish(JSON.parseObject(msgJson));

            if (intervalMs > 0) {
                Thread.sleep(intervalMs);
            }

            if ((i + 1) % 100 == 0) {
                System.out.printf("  已发送 %d / %d, 已接收 %d%n", i + 1, messageCount, receivedCount.get());
            }
        }

        // 等待最后的消息到达
        System.out.println("等待剩余消息到达...");
        Thread.sleep(3000);

        long testDuration = System.currentTimeMillis() - testStart;

        // 输出结果
        System.out.println();
        System.out.println("============ 延迟测试结果 ============");
        System.out.printf("  发送消息数:    %d%n", messageCount);
        System.out.printf("  接收消息数:    %d%n", receivedCount.get());
        System.out.printf("  丢失消息:      %d%n", messageCount - receivedCount.get());
        System.out.printf("  测试总耗时:    %.1f 秒%n", testDuration / 1000.0);
        System.out.printf("  吞吐量:        %.0f msg/s%n", receivedCount.get() * 1000.0 / testDuration);
        System.out.println();

        if (histogram.getTotalCount() > 0) {
            System.out.println("  端到端延迟统计 (从RTopic.publish到客户端收到):");
            System.out.printf("    最小值:  %.2f ms%n", histogram.getMinValue() / 1_000_000.0);
            System.out.printf("    P50:     %.2f ms%n", histogram.getValueAtPercentile(50) / 1_000_000.0);
            System.out.printf("    P90:     %.2f ms%n", histogram.getValueAtPercentile(90) / 1_000_000.0);
            System.out.printf("    P95:     %.2f ms%n", histogram.getValueAtPercentile(95) / 1_000_000.0);
            System.out.printf("    P99:     %.2f ms%n", histogram.getValueAtPercentile(99) / 1_000_000.0);
            System.out.printf("    最大值:  %.2f ms%n", histogram.getMaxValue() / 1_000_000.0);
            System.out.printf("    平均值:  %.2f ms%n", histogram.getMean() / 1_000_000.0);
            System.out.println();

            double p99Ms = histogram.getValueAtPercentile(99) / 1_000_000.0;
            if (p99Ms < 100) {
                System.out.println("  >>> 结论: P99 < 100ms -- 端到端延迟达标");
            } else {
                System.out.printf("  >>> 结论: P99 = %.2f ms, 超过100ms -- 需优化%n", p99Ms);
            }
        } else {
            System.out.println("  警告: 未收到任何消息，无法计算延迟。");
            System.out.println("  可能原因:");
            System.out.println("    1. 服务端未启动或RTopic未监听");
            System.out.println("    2. Token无效, WebSocket连接被拒绝");
            System.out.println("    3. Redis序列化方式不兼容(见README)");
        }
        System.out.println("======================================");

        // 清理
        receiverChannel.close();
        redisson.shutdown();
        group.shutdownGracefully();
    }

    private static Channel connectWebSocket(EventLoopGroup group, String host, int port,
                                             String wsUrl, String token, CountDownLatch latch,
                                             SimpleChannelInboundHandler<Object> handler) throws Exception {
        String fullUrl = wsUrl + "?token=" + token;
        WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
            new URI(fullUrl), WebSocketVersion.V13, null, true, new DefaultHttpHeaders());

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(
                        new HttpClientCodec(),
                        new HttpObjectAggregator(65536),
                        new LatencyClientHandler(handshaker, latch)
                    );
                }
            });

        return bootstrap.connect(host, port).sync().channel();
    }

    /**
     * 处理WebSocket握手
     */
    static class LatencyClientHandler extends SimpleChannelInboundHandler<Object> {
        private final WebSocketClientHandshaker handshaker;
        private final CountDownLatch latch;

        LatencyClientHandler(WebSocketClientHandshaker handshaker, CountDownLatch latch) {
            this.handshaker = handshaker;
            this.latch = latch;
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
                    latch.countDown();
                } catch (Exception e) {
                    System.err.println("WebSocket握手失败: " + e.getMessage());
                    latch.countDown();
                }
                return;
            }

            if (msg instanceof TextWebSocketFrame) {
                long receiveNano = System.nanoTime();
                String text = ((TextWebSocketFrame) msg).text();

                try {
                    JSONObject json = JSON.parseObject(text);
                    Object extendData = json.get("extendData");
                    if (extendData instanceof JSONObject) {
                        JSONObject ext = (JSONObject) extendData;
                        Long sendNanoTime = ext.getLong("sendNanoTime");
                        if (sendNanoTime != null) {
                            long latencyNanos = receiveNano - sendNanoTime;
                            if (latencyNanos > 0 && latencyNanos < TimeUnit.SECONDS.toNanos(10)) {
                                synchronized (histogram) {
                                    histogram.recordValue(latencyNanos);
                                }
                                receivedCount.incrementAndGet();
                            }
                        }
                    }
                } catch (Exception e) {
                    // 忽略非benchmark消息(如INIT消息)
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("连接异常: " + cause.getMessage());
            ctx.close();
        }
    }

    /**
     * 占位用, 实际不使用
     */
    static class LatencyMeasureHandler extends SimpleChannelInboundHandler<Object> {
        private final CountDownLatch latch;

        LatencyMeasureHandler(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {}
    }
}
