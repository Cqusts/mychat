package com.easychat.service.impl;

import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.AiHistoryMessageDto;
import com.easychat.service.AiChatMemory;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于Redis的AI对话记忆。
 * 换掉原先的进程内Map：那种实现和项目的RTopic跨节点广播设计是矛盾的，
 * 一旦多实例部署，用户请求打到不同节点，多轮上下文就串不起来了。
 */
@Component("redisAiChatMemory")
public class RedisAiChatMemory implements AiChatMemory {

    private static final Logger logger = LoggerFactory.getLogger(RedisAiChatMemory.class);

    @Resource
    private RedissonClient redissonClient;

    /**
     * 保留的最大对话轮数（一问一答为一轮）
     */
    @Value("${ai.chat.max-history:20}")
    private Integer maxHistory;

    /**
     * 历史记录的过期天数，每次追加都会续期
     */
    @Value("${ai.chat.history.expire-days:7}")
    private Integer expireDays;

    @Override
    public List<Message> load(String userId) {
        try {
            RList<AiHistoryMessageDto> list = getList(userId);
            if (list == null || list.isEmpty()) {
                return Collections.emptyList();
            }
            List<Message> messages = new ArrayList<>();
            for (AiHistoryMessageDto item : list.readAll()) {
                if (item == null || item.getContent() == null) {
                    continue;
                }
                if (AiHistoryMessageDto.ROLE_ASSISTANT.equals(item.getRole())) {
                    messages.add(new AssistantMessage(item.getContent()));
                } else {
                    messages.add(new UserMessage(item.getContent()));
                }
            }
            return messages;
        } catch (Exception e) {
            //Redis异常时退化成无记忆的单轮对话，而不是让整个回复失败
            logger.error("读取AI对话历史失败, userId:{}", userId, e);
            return Collections.emptyList();
        }
    }

    @Override
    public void append(String userId, String userContent, String assistantContent) {
        try {
            RList<AiHistoryMessageDto> list = getList(userId);
            if (list == null) {
                return;
            }
            list.addAll(List.of(
                    new AiHistoryMessageDto(AiHistoryMessageDto.ROLE_USER, userContent),
                    new AiHistoryMessageDto(AiHistoryMessageDto.ROLE_ASSISTANT, assistantContent)
            ));
            //一问一答算一轮，超出部分从头裁掉。
            //trim底层是Redis的LTRIM，单条命令原子完成，不用先读size再逐条删
            int limit = maxHistory * 2;
            int size = list.size();
            if (size > limit) {
                list.trim(size - limit, size - 1);
            }
            list.expire(java.time.Duration.ofDays(expireDays));
        } catch (Exception e) {
            logger.error("写入AI对话历史失败, userId:{}", userId, e);
        }
    }

    @Override
    public void clear(String userId) {
        try {
            RList<AiHistoryMessageDto> list = getList(userId);
            if (list != null) {
                list.delete();
            }
        } catch (Exception e) {
            logger.error("清空AI对话历史失败, userId:{}", userId, e);
        }
    }

    private RList<AiHistoryMessageDto> getList(String userId) {
        if (redissonClient == null) {
            logger.warn("RedissonClient不可用，AI对话降级为无上下文模式");
            return null;
        }
        return redissonClient.getList(Constants.REDIS_KEY_AI_HISTORY + userId);
    }
}
