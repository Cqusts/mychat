package com.easychat.entity.query;


/**
 * 朋友圈评论表参数
 */
public class UserMomentsCommentQuery extends BaseParam {


    /**
     * 评论ID
     */
    private Long commentId;

    /**
     * 动态ID
     */
    private Long momentsId;

    /**
     * 评论用户ID
     */
    private String userId;

    private String userIdFuzzy;

    /**
     * 评论用户昵称
     */
    private String nickName;

    private String nickNameFuzzy;

    /**
     * 回复用户ID
     */
    private String replyUserId;

    private String replyUserIdFuzzy;

    /**
     * 回复用户昵称
     */
    private String replyNickName;

    private String replyNickNameFuzzy;

    /**
     * 评论内容
     */
    private String content;

    private String contentFuzzy;

    /**
     * 评论时间
     */
    private Long createTime;

    /**
     * 状态 0:已删除 1:正常
     */
    private Integer status;


    public void setCommentId(Long commentId) {
        this.commentId = commentId;
    }

    public Long getCommentId() {
        return this.commentId;
    }

    public void setMomentsId(Long momentsId) {
        this.momentsId = momentsId;
    }

    public Long getMomentsId() {
        return this.momentsId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserId() {
        return this.userId;
    }

    public void setUserIdFuzzy(String userIdFuzzy) {
        this.userIdFuzzy = userIdFuzzy;
    }

    public String getUserIdFuzzy() {
        return this.userIdFuzzy;
    }

    public void setNickName(String nickName) {
        this.nickName = nickName;
    }

    public String getNickName() {
        return this.nickName;
    }

    public void setNickNameFuzzy(String nickNameFuzzy) {
        this.nickNameFuzzy = nickNameFuzzy;
    }

    public String getNickNameFuzzy() {
        return this.nickNameFuzzy;
    }

    public void setReplyUserId(String replyUserId) {
        this.replyUserId = replyUserId;
    }

    public String getReplyUserId() {
        return this.replyUserId;
    }

    public void setReplyUserIdFuzzy(String replyUserIdFuzzy) {
        this.replyUserIdFuzzy = replyUserIdFuzzy;
    }

    public String getReplyUserIdFuzzy() {
        return this.replyUserIdFuzzy;
    }

    public void setReplyNickName(String replyNickName) {
        this.replyNickName = replyNickName;
    }

    public String getReplyNickName() {
        return this.replyNickName;
    }

    public void setReplyNickNameFuzzy(String replyNickNameFuzzy) {
        this.replyNickNameFuzzy = replyNickNameFuzzy;
    }

    public String getReplyNickNameFuzzy() {
        return this.replyNickNameFuzzy;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getContent() {
        return this.content;
    }

    public void setContentFuzzy(String contentFuzzy) {
        this.contentFuzzy = contentFuzzy;
    }

    public String getContentFuzzy() {
        return this.contentFuzzy;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    public Long getCreateTime() {
        return this.createTime;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getStatus() {
        return this.status;
    }
}
