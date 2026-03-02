package com.easychat.entity.po;

import java.io.Serializable;


/**
 * 朋友圈评论表
 */
public class UserMomentsComment implements Serializable {


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

    /**
     * 评论用户昵称
     */
    private String nickName;

    /**
     * 回复用户ID(null表示直接评论)
     */
    private String replyUserId;

    /**
     * 回复用户昵称
     */
    private String replyNickName;

    /**
     * 评论内容
     */
    private String content;

    /**
     * 评论时间(毫秒时间戳)
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

    public void setNickName(String nickName) {
        this.nickName = nickName;
    }

    public String getNickName() {
        return this.nickName;
    }

    public void setReplyUserId(String replyUserId) {
        this.replyUserId = replyUserId;
    }

    public String getReplyUserId() {
        return this.replyUserId;
    }

    public void setReplyNickName(String replyNickName) {
        this.replyNickName = replyNickName;
    }

    public String getReplyNickName() {
        return this.replyNickName;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getContent() {
        return this.content;
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

    @Override
    public String toString() {
        return "评论ID:" + (commentId == null ? "空" : commentId) + "，动态ID:" + (momentsId == null ? "空" : momentsId) + "，用户ID:" + (userId == null ? "空" : userId) + "，昵称:" + (nickName == null ? "空" : nickName) + "，回复用户ID:" + (replyUserId == null ? "空" : replyUserId) + "，内容:" + (content == null ? "空" : content) + "，评论时间:" + (createTime == null ? "空" : createTime) + "，状态:" + (status == null ? "空" : status);
    }
}
