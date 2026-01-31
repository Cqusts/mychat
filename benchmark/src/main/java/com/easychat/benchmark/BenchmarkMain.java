package com.easychat.benchmark;

/**
 * EasyChat 性能基准测试入口
 *
 * 用法:
 *   java -jar benchmark.jar concurrent  --ws-url ws://localhost:5051/ws --tokens-file tokens.txt --connections 5000
 *   java -jar benchmark.jar latency     --ws-url ws://localhost:5051/ws --redis-url redis://localhost:6379 --tokens-file tokens.txt --messages 1000
 *   java -jar benchmark.jar gen-tokens  --api-url http://localhost:5050 --count 5000 --output tokens.txt
 */
public class BenchmarkMain {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }

        String mode = args[0];
        switch (mode) {
            case "concurrent":
                ConcurrentConnectionBenchmark.run(args);
                break;
            case "latency":
                LatencyBenchmark.run(args);
                break;
            case "gen-tokens":
                TokenGenerator.run(args);
                break;
            default:
                printUsage();
        }
    }

    private static void printUsage() {
        System.out.println("EasyChat Benchmark Tool");
        System.out.println("=======================");
        System.out.println();
        System.out.println("用法: java -jar benchmark.jar <mode> [options]");
        System.out.println();
        System.out.println("模式:");
        System.out.println("  concurrent   并发WebSocket长连接压测");
        System.out.println("  latency      端到端消息延迟测试");
        System.out.println("  gen-tokens   批量生成测试用户Token(直接写入Redis, 绕过登录)");
        System.out.println();
        System.out.println("concurrent 选项:");
        System.out.println("  --ws-url <url>          WebSocket地址 (默认 ws://localhost:5051/ws)");
        System.out.println("  --tokens-file <file>    Token文件路径 (每行一个token)");
        System.out.println("  --connections <n>        目标连接数 (默认 5000)");
        System.out.println("  --ramp-up <n>           每秒建立连接数 (默认 200)");
        System.out.println("  --hold-seconds <n>      全部连接建立后保持时间秒 (默认 60)");
        System.out.println("  --heartbeat-interval <n> 心跳间隔秒 (默认 30)");
        System.out.println();
        System.out.println("latency 选项:");
        System.out.println("  --ws-url <url>          WebSocket地址 (默认 ws://localhost:5051/ws)");
        System.out.println("  --redis-url <url>       Redis地址 (默认 redis://localhost:6379)");
        System.out.println("  --tokens-file <file>    Token文件路径 (至少需要2个token)");
        System.out.println("  --messages <n>           发送消息数 (默认 1000)");
        System.out.println("  --interval-ms <n>        消息发送间隔ms (默认 100)");
        System.out.println();
        System.out.println("gen-tokens 选项:");
        System.out.println("  --redis-url <url>       Redis地址 (默认 redis://localhost:6379)");
        System.out.println("  --db-url <url>          MySQL JDBC地址 (默认 jdbc:mysql://127.0.0.1:3306/easychat)");
        System.out.println("  --db-user <user>        MySQL用户名 (默认 root)");
        System.out.println("  --db-password <pwd>     MySQL密码 (默认 111111)");
        System.out.println("  --count <n>             生成Token数量 (默认 5000)");
        System.out.println("  --output <file>         输出文件路径 (默认 tokens.txt)");
    }
}
