package com.easychat.benchmark;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.easychat.entity.dto.MessageSendDto;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 端到端消息延迟测试
 *
 * 测试原理:
 * 1. 建立一个WebSocket接收端连接
 * 2. 通过Redisson RTopic发布MessageSendDto(与服务端同包同类，序列化兼容)
 * 3. 消息体messageContent中嵌入发送时的nanoTime时间戳
 * 4. 接收端收到后解析时间戳计算延迟
 *
 * 完整链路:
 *   RTopic.publish(MessageSendDto) -> Redis广播 -> MessageHandler.lisMessage()
 *   -> channelContextUtils.sendMessage() -> send2User() -> Channel.writeAndFlush()
 *   -> 客户端WebSocket收到
 */
public class LatencyBenchmark {

    private static final String MESSAGE_TOPIC = "message.topic";
    private static final Histogram histogram = new Histogram(TimeUnit.SECONDS.toNanos(10), 3);
    private static final AtomicLong messageIdSeq = new AtomicLong(0);
    private static final AtomicLong receivedCount = new AtomicLong(0);

    // messageContent中嵌入nanoTime的前缀标记
    private static final String NANO_PREFIX = "BENCH_NANO:";

    public static void run(String[] args) throws Exception {
        ArgParser p = new ArgParser(args);
        String wsUrl = p.get("ws-url", "ws://localhost:5051/ws");
        String redisUrl = p.get("redis-url", "redis://localhost:6379");
        String tokensFile = p.get("tokens-file", "tokens.txt");
        int messageCount = p.getInt("messages", 1000);
        long intervalMs = p.getLong("interval-ms", 100);

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

        // 接收端userId: gen-tokens生成的第一个用户，以U开头才能被服务端路由到send2User
        String receiverUserId = "U_BENCH_0000";

        System.out.println("========================================");
        System.out.println("  EasyChat 端到端消息延迟测试");
        System.out.println("========================================");
        System.out.printf("  接收端用户:  %s%n", receiverUserId);
        System.out.printf("  消息数量:    %d%n", messageCount);
        System.out.printf("  发送间隔:    %d ms%n", intervalMs);
        System.out.printf("  Redis:       %s%n", redisUrl);
        System.out.printf("  WS URL:      %s%n", wsUrl);
        System.out.println("========================================");

        URI uri = new URI(wsUrl);
        String host = uri.getHost();
        int port = uri.getPort() == -1 ? 80 : uri.getPort();

        EventLoopGroup group = new NioEventLoopGroup(2);

        String receiverToken = tokens.get(0);

        System.out.println("正在建立WebSocket接收端连接...");
        CountDownLatch connectLatch = new CountDownLatch(1);
        String fullUrl = wsUrl + "?token=" + receiverToken;
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
                        new LatencyClientHandler(handshaker, connectLatch)
                    );
                }
            });

        Channel receiverChannel = bootstrap.connect(host, port).sync().channel();

        if (!connectLatch.await(10, TimeUnit.SECONDS)) {
            System.out.println("错误: WebSocket连接超时");
            group.shutdownGracefully();
            return;
        }
        System.out.println("接收端已连接。");

        // 等待服务端初始化消息(addContext中会发送INIT数据)
        Thread.sleep(2000);

        // 创建Redisson客户端用于发布消息
        Config config = new Config();
        config.useSingleServer().setAddress(redisUrl);
        RedissonClient redisson = Redisson.create(config);

        System.out.printf("开始发送 %d 条消息...%n%n", messageCount);
        long testStart = System.currentTimeMillis();

        RTopic topic = redisson.getTopic(MESSAGE_TOPIC);

        for (int i = 0; i < messageCount; i++) {
            long msgId = messageIdSeq.incrementAndGet();

            // 构造与服务端完全兼容的MessageSendDto(同包名、同serialVersionUID)
            MessageSendDto<String> dto = new MessageSendDto<>();
            dto.setMessageId(msgId);
            // contactId必须以"U"开头，服务端UserContactTypeEnum.getByPrefix取首字符路由
            dto.setContactId(receiverUserId);
            dto.setSendUserId("U_BENCH_SENDER");
            dto.setSendUserNickName("sender");
            // 在messageContent中嵌入发送时的nanoTime用于计算延迟
            dto.setMessageContent(NANO_PREFIX + System.nanoTime());
            dto.setMessageType(2);
            dto.setSendTime(System.currentTimeMillis());
            dto.setContactType(0);

            topic.publish(dto);

            if (intervalMs > 0) {
                Thread.sleep(intervalMs);
            }

            if ((i + 1) % 100 == 0) {
                System.out.printf("  已发送 %d / %d, 已接收 %d%n", i + 1, messageCount, receivedCount.get());
            }
        }

        System.out.println("等待剩余消息到达...");
        Thread.sleep(3000);

        long testDuration = System.currentTimeMillis() - testStart;

        System.out.println();
        System.out.println("============ 延迟测试结果 ============");
        System.out.printf("  发送消息数:    %d%n", messageCount);
        System.out.printf("  接收消息数:    %d%n", receivedCount.get());
        System.out.printf("  丢失消息:      %d%n", messageCount - receivedCount.get());
        System.out.printf("  测试总耗时:    %.1f 秒%n", testDuration / 1000.0);
        System.out.printf("  吞吐量:        %.0f msg/s%n", receivedCount.get() * 1000.0 / testDuration);
        System.out.println();

        if (histogram.getTotalCount() > 0) {
            System.out.println("  端到端延迟统计 (RTopic.publish -> WebSocket客户端收到):");
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
            System.out.println("    3. contactId路由问题(userId必须以U开头)");
        }
        System.out.println("======================================");

        receiverChannel.close();
        redisson.shutdown();
        group.shutdownGracefully();
    }

    /**
     * WebSocket客户端Handler: 握手 + 接收消息计算延迟
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
                    String content = json.getString("messageContent");
                    if (content != null && content.startsWith(NANO_PREFIX)) {
                        long sendNano = Long.parseLong(content.substring(NANO_PREFIX.length()));
                        long latencyNanos = receiveNano - sendNano;
                        if (latencyNanos > 0 && latencyNanos < TimeUnit.SECONDS.toNanos(10)) {
                            synchronized (histogram) {
                                histogram.recordValue(latencyNanos);
                            }
                            receivedCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    // 忽略非benchmark消息(INIT数据等)
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("连接异常: " + cause.getMessage());
            ctx.close();
        }
    }
}
