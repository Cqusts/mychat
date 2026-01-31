package com.easychat.benchmark;

import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.UUID;

/**
 * 直接向Redis写入测试用的Token，同时向MySQL插入测试用户记录。
 * 这样可以快速生成大量可用于WebSocket连接的token，无需真实用户账号。
 */
public class TokenGenerator {

    private static final String REDIS_KEY_WS_TOKEN = "easychat:ws:token:";
    private static final String REDIS_KEY_WS_TOKEN_USERID = "easychat:ws:token:userid:";
    private static final long TOKEN_EXPIRE_SECONDS = 48 * 3600;

    public static void run(String[] args) throws Exception {
        ArgParser p = new ArgParser(args);
        String redisUrl = p.get("redis-url", "redis://localhost:6379");
        String dbUrl = p.get("db-url", "jdbc:mysql://127.0.0.1:3306/easychat?useUnicode=true&characterEncoding=utf8");
        String dbUser = p.get("db-user", "root");
        String dbPassword = p.get("db-password", "111111");
        int count = p.getInt("count", 5000);
        String output = p.get("output", "tokens.txt");

        Config config = new Config();
        config.useSingleServer().setAddress(redisUrl);
        RedissonClient redisson = Redisson.create(config);

        // 连接MySQL，插入测试用户
        Class.forName("com.mysql.cj.jdbc.Driver");
        Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
        conn.setAutoCommit(false);

        String insertSql = "INSERT IGNORE INTO user_info (user_id, nick_name, email, sex, status, password, " +
            "join_type, create_time, last_login_time, last_off_time) " +
            "VALUES (?, ?, ?, 1, 1, ?, 0, NOW(), NOW(), ?)";
        PreparedStatement ps = conn.prepareStatement(insertSql);

        System.out.printf("正在生成 %d 个测试用户和Token...%n", count);
        System.out.printf("  Redis:  %s%n", redisUrl);
        System.out.printf("  MySQL:  %s%n", dbUrl);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(output))) {
            for (int i = 0; i < count; i++) {
                String userId = String.format("BENCH_%06d", i);
                String token = md5(userId + UUID.randomUUID().toString());

                // 1. 写入MySQL用户记录
                ps.setString(1, userId);
                ps.setString(2, "bench_" + i);
                ps.setString(3, "bench_" + i + "@test.local");
                ps.setString(4, md5("bench123"));
                ps.setLong(5, System.currentTimeMillis());
                ps.addBatch();

                // 2. 写入Redis token (兼容GenericJackson2JsonRedisSerializer)
                String tokenJson = String.format(
                    "{\"@class\":\"com.easychat.entity.dto.TokenUserInfoDto\","
                    + "\"token\":\"%s\",\"userId\":\"%s\",\"nickName\":\"bench_%d\",\"admin\":false}",
                    token, userId, i
                );

                RBucket<String> tokenBucket = redisson.getBucket(
                    REDIS_KEY_WS_TOKEN + token, StringCodec.INSTANCE);
                tokenBucket.set(tokenJson, Duration.ofSeconds(TOKEN_EXPIRE_SECONDS));

                RBucket<String> userBucket = redisson.getBucket(
                    REDIS_KEY_WS_TOKEN_USERID + userId, StringCodec.INSTANCE);
                userBucket.set("\"" + token + "\"", Duration.ofSeconds(TOKEN_EXPIRE_SECONDS));

                writer.write(token);
                writer.newLine();

                // 每500条批量提交一次
                if ((i + 1) % 500 == 0) {
                    ps.executeBatch();
                    conn.commit();
                    System.out.printf("  已生成 %d / %d%n", i + 1, count);
                }
            }

            // 提交剩余
            ps.executeBatch();
            conn.commit();
        }

        ps.close();
        conn.close();
        redisson.shutdown();

        System.out.printf("完成! %d 个用户已写入MySQL, Token已写入 %s%n", count, output);
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
