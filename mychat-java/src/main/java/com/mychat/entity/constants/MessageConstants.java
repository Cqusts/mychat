package com.mychat.entity.constants;

/**
 * 消息相关常量
 */
public class MessageConstants {

    /**
     * 消息内容最大长度（字符数），超限即拒绝。
     * 暂定值，后续如需调整走配置中心，勿在注解中硬编码。
     */
    public static final int MAX_CONTENT_LENGTH = 5000;
}
