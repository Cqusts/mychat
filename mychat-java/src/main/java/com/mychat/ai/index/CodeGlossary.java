package com.mychat.ai.index;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 中文需求 → 代码标识符的桥。
 *
 * 这是整套检索里最要紧的一环。需求是中文写的（"会话列表支持模糊搜索"），
 * 而代码里叫 ChatSessionUser、chat_session_user。光靠中文注释碰运气不够：
 * 实测发现注释里出现某个词的文件到处都是，而真正要改的实现类
 * 反而可能一句中文注释都没有。
 *
 * 这本词典把领域词翻成标识符，查询时一起扔进召回。
 * 内容是照着这个项目的实际命名整理的，换项目要重新配
 */
public final class CodeGlossary {

    /**
     * 中文词 -> 代码里对应的英文标识符
     */
    private static final Map<String, List<String>> TERMS = Map.ofEntries(
            Map.entry("会话", List.of("session", "chatsession")),
            Map.entry("消息", List.of("message", "chatmessage")),
            Map.entry("聊天", List.of("chat", "message")),
            Map.entry("群聊", List.of("group", "groupinfo")),
            Map.entry("群组", List.of("group", "groupinfo")),
            Map.entry("群主", List.of("groupowner", "owner")),
            Map.entry("群公告", List.of("groupnotice", "notice")),
            Map.entry("公告", List.of("notice")),
            Map.entry("好友", List.of("contact", "usercontact", "friend")),
            Map.entry("联系人", List.of("contact", "usercontact")),
            Map.entry("申请", List.of("apply", "usercontactapply")),
            Map.entry("用户", List.of("user", "userinfo")),
            Map.entry("昵称", List.of("nickname", "nickName")),
            Map.entry("头像", List.of("avatar", "cover")),
            Map.entry("登录", List.of("login", "account")),
            Map.entry("注册", List.of("register", "account")),
            Map.entry("撤回", List.of("revoke", "recall", "withdraw")),
            Map.entry("已读", List.of("read", "readed", "noread")),
            Map.entry("回执", List.of("receipt", "read")),
            Map.entry("引用", List.of("quote", "reply", "reference")),
            Map.entry("搜索", List.of("search", "query")),
            Map.entry("分页", List.of("page", "pageno", "pagesize", "simplepage")),
            Map.entry("排序", List.of("orderby", "order", "sort")),
            Map.entry("校验", List.of("valid", "validation", "check")),
            Map.entry("上限", List.of("limit", "max", "capacity")),
            Map.entry("次数", List.of("count", "times", "limit")),
            Map.entry("助手", List.of("agent", "robot", "aiagent")),
            Map.entry("文件", List.of("file", "upload")),
            Map.entry("视频", List.of("video", "media")),
            Map.entry("图片", List.of("image", "cover")),
            Map.entry("发送", List.of("send", "sendmessage")),
            Map.entry("接口", List.of("controller", "api")),
            Map.entry("字段", List.of("column", "field")),
            Map.entry("表", List.of("table", "mapper")),
            Map.entry("开关", List.of("enable", "status", "flag")),
            Map.entry("权限", List.of("permission", "role", "status")),
            Map.entry("拒绝", List.of("reject", "refuse", "forbidden")),
            Map.entry("提示", List.of("message", "info", "tip")));

    /**
     * 需求文里的套话，对定位文件毫无帮助，反而会把带这些词的注释全捞出来
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "增加", "新增", "添加", "支持", "实现", "提供", "返回", "需求", "功能",
            "可以", "能够", "应该", "需要", "进行", "使用", "通过", "并且", "然后",
            "以及", "对应", "相关", "一个", "这个", "那个", "所有", "其他", "比如",
            "例如", "同步", "更新", "修改", "确保", "注意", "友好", "明确", "自动",
            "时候", "之后", "之前", "同时", "分别", "各自", "以下", "如下", "内容",
            "信息", "数据", "系统", "项目", "代码", "方法", "参数", "结果", "处理");

    private CodeGlossary() {
    }

    /**
     * 把查询扩展一遍：原词 + 领域词映射出来的标识符，同时去掉套话
     */
    public static List<String> expand(String query) {
        Set<String> expanded = new LinkedHashSet<>();
        for (String token : CodeToken.tokenize(query)) {
            if (STOP_WORDS.contains(token)) {
                continue;
            }
            expanded.add(token);
            List<String> mapped = TERMS.get(token);
            if (mapped != null) {
                expanded.addAll(mapped);
            }
        }
        return new ArrayList<>(expanded);
    }

    public static boolean isStopWord(String token) {
        return STOP_WORDS.contains(token);
    }
}
