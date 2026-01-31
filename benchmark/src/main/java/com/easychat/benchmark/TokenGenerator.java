package com.easychat.benchmark;

import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.Serializable;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.UUID;

/**
 * 直接向Redis写入测试用的Token，绕过登录接口。
 * 这样可以快速生成大量可用于WebSocket连接的token，无需真实用户账号。
 */
public class TokenGenerator {

    // 与 Constants.java 中保持一致
    private static final String REDIS_KEY_WS_TOKEN = "easychat:ws:token:";
    private static final String REDIS_KEY_WS_TOKEN_USERID = "easychat:ws:token:userid:";
    private static final long TOKEN_EXPIRE_SECONDS = 48 * 3600; // 48小时

    public static void run(String[] args) throws Exception {
        ArgParser p = new ArgParser(args);
        String redisUrl = p.get("redis-url", "redis://localhost:6379");
        int count = p.getInt("count", 5000);
        String output = p.get("output", "tokens.txt");

        Config config = new Config();
        config.useSingleServer().setAddress(redisUrl);
        RedissonClient redisson = Redisson.create(config);

        System.out.printf("正在向 Redis(%s) 写入 %d 个测试Token...%n", redisUrl, count);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(output))) {
            for (int i = 0; i < count; i++) {
                String userId = String.format("BENCH_%06d", i);
                String token = md5(userId + UUID.randomUUID().toString());

                // 构造 TokenUserInfoDto 的JSON，与服务端反序列化兼容
                String tokenJson = String.format(
                    "{\"token\":\"%s\",\"userId\":\"%s\",\"nickName\":\"bench_%d\",\"admin\":false}",
                    token, userId, i
                );

                // 写入 token -> TokenUserInfoDto
                RBucket<Object> tokenBucket = redisson.getBucket(REDIS_KEY_WS_TOKEN + token);
                tokenBucket.set(tokenJson, Duration.ofSeconds(TOKEN_EXPIRE_SECONDS));

                // 写入 userId -> token
                RBucket<Object> userBucket = redisson.getBucket(REDIS_KEY_WS_TOKEN_USERID + userId);
                userBucket.set(token, Duration.ofSeconds(TOKEN_EXPIRE_SECONDS));

                writer.write(token);
                writer.newLine();

                if ((i + 1) % 500 == 0) {
                    System.out.printf("  已生成 %d / %d%n", i + 1, count);
                }
            }
        }

        System.out.printf("完成! Token已写入 %s%n", output);
        System.out.println("注意: 服务端RedisComponet.getTokenUserInfoDto()使用的是Spring RedisTemplate反序列化,");
        System.out.println("如果序列化方式不兼容，请改用下面的方式手动写入。参考 README。");

        redisson.shutdown();
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
