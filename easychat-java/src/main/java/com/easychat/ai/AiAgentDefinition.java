package com.easychat.ai;

/**
 * 一个AI助手的定义。
 * 从配置读取，加一个助手只需要加一段配置，不用改代码。
 */
public class AiAgentDefinition {

    /**
     * 助手的用户ID，必须以U开头（和普通用户同一套命名空间），且不超过12个字符——
     * user_info.user_id字段长度就是12
     */
    private String id;

    /**
     * 群里显示的昵称，同时也是@提及时匹配的名字
     */
    private String name;

    /**
     * 人设，作为system prompt下发
     */
    private String prompt;

    /**
     * 个性签名，一句话标签
     */
    private String signature;

    /**
     * 能力说明，告诉用户这个助手能帮你做什么。
     * 和prompt的区别：prompt是给模型看的人设，这个是给人看的功能介绍。
     * 多条能力用 | 分隔，前端会拆成列表展示。
     */
    private String description;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
