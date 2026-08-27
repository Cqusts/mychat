package com.easychat.ai;

import com.easychat.entity.enums.JoinTypeEnum;
import com.easychat.entity.enums.UserStatusEnum;
import com.easychat.entity.po.UserInfo;
import com.easychat.entity.query.UserInfoQuery;
import com.easychat.mappers.UserInfoMapper;
import com.easychat.utils.StringTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Date;

/**
 * 启动时把配置里的AI助手写进user_info表。
 *
 * 助手必须有真实的user_info记录，否则群成员列表（查询时join了user_info）里看不到它们，
 * 头像和昵称也显示不出来。有了这条记录，助手在系统里和真人成员就是完全一样的存在。
 */
@Component("aiAgentInitializer")
@Order(1)
public class AiAgentInitializer implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(AiAgentInitializer.class);

    /**
     * user_info.user_id字段长度
     */
    private static final int MAX_USER_ID_LENGTH = 12;

    @Resource
    private AiAgentRegistry aiAgentRegistry;

    @Resource
    private UserInfoMapper<UserInfo, UserInfoQuery> userInfoMapper;

    @Override
    public void run(ApplicationArguments args) {
        for (AiAgentDefinition agent : aiAgentRegistry.getAgents()) {
            try {
                initAgent(agent);
            } catch (Exception e) {
                //某个助手初始化失败不能影响服务启动
                logger.error("初始化AI助手失败, agentId:{}", agent.getId(), e);
            }
        }
    }

    private void initAgent(AiAgentDefinition agent) {
        if (StringTools.isEmpty(agent.getId()) || StringTools.isEmpty(agent.getName())) {
            logger.warn("AI助手配置不完整，已跳过: id={}, name={}", agent.getId(), agent.getName());
            return;
        }
        if (agent.getId().length() > MAX_USER_ID_LENGTH) {
            logger.error("AI助手ID超过{}个字符，无法写入user_info, agentId:{}", MAX_USER_ID_LENGTH, agent.getId());
            return;
        }
        UserInfo exist = userInfoMapper.selectByUserId(agent.getId());
        if (exist == null) {
            UserInfo userInfo = new UserInfo();
            userInfo.setUserId(agent.getId());
            userInfo.setNickName(agent.getName());
            userInfo.setPersonalSignature(agent.getSignature());
            //email留空——它上面有唯一索引，但允许NULL，多个助手不会冲突
            userInfo.setJoinType(JoinTypeEnum.JOIN.getType());
            userInfo.setStatus(UserStatusEnum.ENABLE.getStatus());
            userInfo.setCreateTime(new Date());
            userInfoMapper.insert(userInfo);
            logger.info("AI助手已创建: {}({})", agent.getName(), agent.getId());
            return;
        }
        //已存在就只同步昵称和签名，改配置能直接生效，其它字段不动
        boolean nameChanged = !agent.getName().equals(exist.getNickName());
        boolean signChanged = agent.getSignature() != null
                && !agent.getSignature().equals(exist.getPersonalSignature());
        if (nameChanged || signChanged) {
            UserInfo update = new UserInfo();
            update.setNickName(agent.getName());
            update.setPersonalSignature(agent.getSignature());
            userInfoMapper.updateByUserId(update, agent.getId());
            logger.info("AI助手信息已更新: {}({})", agent.getName(), agent.getId());
        }
    }
}
