package com.easychat.ai;

import com.easychat.entity.config.AppConfig;
import com.easychat.entity.constants.Constants;
import com.easychat.entity.enums.JoinTypeEnum;
import com.easychat.entity.enums.UserStatusEnum;
import com.easychat.entity.po.UserInfo;
import com.easychat.entity.query.UserInfoQuery;
import com.easychat.mappers.UserInfoMapper;
import com.easychat.utils.StringTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import jakarta.annotation.Resource;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Date;
import java.util.List;

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

    /**
     * 配置文件里的占位符，等同于没配
     */
    private static final String API_KEY_PLACEHOLDER = "YOUR_API_KEY_HERE";

    @Resource
    private AiAgentRegistry aiAgentRegistry;

    @Resource
    private UserInfoMapper<UserInfo, UserInfoQuery> userInfoMapper;

    @Resource
    private AppConfig appConfig;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model:}")
    private String model;

    /**
     * 生成的默认头像边长
     */
    private static final int AVATAR_SIZE = 128;

    /**
     * 助手头像底色，按配置顺序轮流取，让几个助手一眼能区分开
     */
    private static final Color[] AVATAR_COLORS = new Color[]{
            new Color(0x07, 0xC1, 0x60),
            new Color(0x57, 0x6B, 0x95),
            new Color(0xFA, 0x9D, 0x3B),
            new Color(0x8E, 0x67, 0xD8),
            new Color(0x14, 0x85, 0xEE)
    };

    @Override
    public void run(ApplicationArguments args) {
        checkApiKey();
        List<AiAgentDefinition> agents = aiAgentRegistry.getAgents();
        for (int i = 0; i < agents.size(); i++) {
            AiAgentDefinition agent = agents.get(i);
            try {
                initAgent(agent);
                initAvatar(agent, i);
            } catch (Exception e) {
                //某个助手初始化失败不能影响服务启动
                logger.error("初始化AI助手失败, agentId:{}", agent.getId(), e);
            }
        }
    }

    /**
     * 启动时检查API Key有没有真的配上。
     * 不检查的话，用户要一直到发消息收到"AI助手暂时无法回复"才知道，
     * 而群聊里连这条兜底消息都不会有，等于完全没有反馈。
     */
    private void checkApiKey() {
        if (StringTools.isEmpty(apiKey) || API_KEY_PLACEHOLDER.equals(apiKey)) {
            logger.error("=================================================================");
            logger.error("AI功能不可用：spring.ai.openai.api-key 还没有配置。");
            logger.error("请设置环境变量 EASYCHAT_AI_API_KEY，或直接改 application.yml。");
            logger.error("在此之前，所有AI对话都会回复\"AI助手暂时无法回复\"。");
            logger.error("=================================================================");
            return;
        }
        logger.info("AI大模型已配置: base-url={}, model={}", baseUrl, model);
    }

    /**
     * 给助手生成一张默认头像。
     * 头像接口对不存在的文件直接返回错误，前端就会显示一个灰色的破图标；
     * 助手又没有上传头像的入口，所以这里补一张，聊天列表、消息气泡、群成员列表就都正常了。
     * 已经存在的不覆盖——管理员手动放了图就以他的为准。
     */
    private void initAvatar(AiAgentDefinition agent, int index) {
        if (StringTools.isEmpty(agent.getId())) {
            return;
        }
        try {
            File avatarFolder = new File(appConfig.getProjectFolder()
                    + Constants.FILE_FOLDER_FILE + Constants.FILE_FOLDER_AVATAR_NAME);
            if (!avatarFolder.exists() && !avatarFolder.mkdirs()) {
                logger.warn("头像目录创建失败，跳过助手默认头像生成: {}", avatarFolder.getPath());
                return;
            }
            File avatarFile = new File(avatarFolder.getPath() + "/" + agent.getId() + Constants.IMAGE_SUFFIX);
            //封面图是原图路径直接拼后缀，和UserInfoServiceImpl里的写法保持一致
            File coverFile = new File(avatarFile.getPath() + Constants.COVER_IMAGE_SUFFIX);
            if (avatarFile.exists() && coverFile.exists()) {
                return;
            }
            BufferedImage image = drawRobotAvatar(AVATAR_COLORS[index % AVATAR_COLORS.length]);
            ImageIO.write(image, "png", avatarFile);
            ImageIO.write(image, "png", coverFile);
            logger.info("已生成AI助手默认头像: {}", avatarFile.getPath());
        } catch (Exception e) {
            //头像只是显示效果，生成失败不影响助手可用
            logger.warn("生成AI助手默认头像失败, agentId:{}", agent.getId(), e);
        }
    }

    /**
     * 画一个简单的机器人头像。
     * 只用几何图形不写文字：服务器上不一定装了中文字体，画字容易变成方框。
     */
    private BufferedImage drawRobotAvatar(Color background) {
        BufferedImage image = new BufferedImage(AVATAR_SIZE, AVATAR_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(background);
            g.fillRect(0, 0, AVATAR_SIZE, AVATAR_SIZE);

            g.setColor(Color.WHITE);
            //天线
            g.setStroke(new BasicStroke(5f));
            g.drawLine(64, 26, 64, 40);
            g.fillOval(58, 18, 12, 12);
            //头
            g.fillRoundRect(30, 40, 68, 56, 18, 18);
            //眼睛（挖成底色）
            g.setColor(background);
            g.fillOval(45, 58, 14, 14);
            g.fillOval(69, 58, 14, 14);
            //嘴
            g.fillRoundRect(50, 80, 28, 7, 4, 4);
        } finally {
            g.dispose();
        }
        return image;
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
