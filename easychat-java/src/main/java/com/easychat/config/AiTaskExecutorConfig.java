package com.easychat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * AI任务线程池。
 * 调用大模型是典型的IO密集型操作（单次耗时秒级，Agent多轮工具调用可能更久），
 * 绝不能占用Tomcat的HTTP工作线程，否则并发一上来连接数就被拖垮。
 */
@Configuration
public class AiTaskExecutorConfig {

    @Value("${ai.chat.executor.core-size:16}")
    private Integer coreSize;

    @Value("${ai.chat.executor.max-size:64}")
    private Integer maxSize;

    @Value("${ai.chat.executor.queue-capacity:64}")
    private Integer queueCapacity;

    @Value("${ai.chat.executor.keep-alive-seconds:60}")
    private Integer keepAliveSeconds;

    @Value("${ai.workflow.executor.core-size:4}")
    private Integer workflowCoreSize;

    @Value("${ai.workflow.executor.max-size:8}")
    private Integer workflowMaxSize;

    @Value("${ai.workflow.executor.queue-capacity:16}")
    private Integer workflowQueueCapacity;

    @Bean(name = "aiTaskExecutor")
    public ThreadPoolTaskExecutor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        //JDK线程池的扩容规则是"队列满了才加线程"，所以队列不能设太深，
        //否则请求会在队列里排到超时，还不如快速失败让用户重试
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix("ai-chat-");
        //队列和线程都满时直接抛RejectedExecutionException，
        //由调用方兜底回一条"助手繁忙"，不用CallerRunsPolicy是因为那样又会阻塞HTTP线程
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        //关机时给在途的AI回复留出收尾时间，避免用户看到半截消息
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 需求流水线专用线程池，和上面的聊天池分开。
     *
     * 为什么必须分开：聊天回复是秒级的，而一条流水线要跑五个阶段、
     * 单阶段墙钟上限就有20分钟，一个任务能占住一个线程几十分钟。
     * 混在一个池里的话，几条流水线跑起来就会把普通用户的单聊消息挤到队列后面，
     * 典型的队头阻塞——用户发条消息要等别人的编码任务跑完才有回音。
     *
     * 这个池刻意开得小：流水线本来就不该并发太多，
     * 每条都在磁盘上改代码、跑maven，并发高了机器也扛不住。
     */
    @Bean(name = "aiWorkflowExecutor")
    public ThreadPoolTaskExecutor aiWorkflowExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workflowCoreSize);
        executor.setMaxPoolSize(workflowMaxSize);
        executor.setQueueCapacity(workflowQueueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix("ai-workflow-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
