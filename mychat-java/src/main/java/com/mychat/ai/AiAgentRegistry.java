package com.mychat.ai;

import com.mychat.entity.constants.Constants;
import com.mychat.utils.StringTools;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI助手注册表。
 * 助手在系统里就是一种特殊用户：有自己的userId和昵称，能进群、能发消息，
 * 走的是和真人完全一样的消息通路，区别只在于回复由大模型生成。
 */
@Component("aiAgentRegistry")
@ConfigurationProperties(prefix = "ai")
public class AiAgentRegistry {

    /**
     * 由配置注入，形如 ai.agents[0].id=Uagentpm
     */
    private List<AiAgentDefinition> agents = new ArrayList<>();

    /**
     * id -> 定义，构造顺序保持配置顺序
     */
    private final Map<String, AiAgentDefinition> agentMap = new LinkedHashMap<>();

    public void setAgents(List<AiAgentDefinition> agents) {
        this.agents = agents == null ? new ArrayList<>() : agents;
        this.agentMap.clear();
        for (AiAgentDefinition agent : this.agents) {
            if (agent != null && !StringTools.isEmpty(agent.getId())) {
                this.agentMap.put(agent.getId(), agent);
            }
        }
    }

    public List<AiAgentDefinition> getAgents() {
        return Collections.unmodifiableList(agents);
    }

    public AiAgentDefinition getById(String userId) {
        return agentMap.get(userId);
    }

    /**
     * 判断一个userId是不是AI助手。
     * 单聊机器人ROBOT_UID也算，它和群助手共用同一套"不是真人"的处理逻辑
     * （比如跳过好友关系校验——助手不需要先加好友才能收发消息）。
     */
    public boolean isAgent(String userId) {
        if (StringTools.isEmpty(userId)) {
            return false;
        }
        return Constants.ROBOT_UID.equals(userId) || agentMap.containsKey(userId);
    }

    /**
     * 从消息内容里解析出被@到的助手。
     *
     * @param content         消息内容
     * @param groupAgentIds   这个群里实际存在的助手ID，不在群里的助手不会被触发
     * @return 被@到的助手，按配置顺序
     */
    public List<AiAgentDefinition> matchMentions(String content, List<String> groupAgentIds) {
        if (StringTools.isEmpty(content) || groupAgentIds == null || groupAgentIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<AiAgentDefinition> matched = new ArrayList<>();
        for (String agentId : groupAgentIds) {
            AiAgentDefinition agent = agentMap.get(agentId);
            if (agent == null || StringTools.isEmpty(agent.getName())) {
                continue;
            }
            if (content.contains("@" + agent.getName())) {
                matched.add(agent);
            }
        }
        return matched;
    }
}
