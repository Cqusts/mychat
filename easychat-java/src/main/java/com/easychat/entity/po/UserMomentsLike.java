package com.easychat.entity.po;

import java.io.Serializable;


/**
 * 朋友圈点赞表
 */
public class UserMomentsLike implements Serializable {


    /**
     * 动态ID
     */
    private Long momentsId;

    /**
     * 点赞用户ID
     */
    private String userId;

    /**
     * 点赞用户昵称
     */
    private String nickName;

    /**
     * 点赞时间(毫秒时间戳)
     */
    private Long createTime;


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

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    public Long getCreateTime() {
        return this.createTime;
    }

    @Override
    public String toString() {
        return "动态ID:" + (momentsId == null ? "空" : momentsId) + "，用户ID:" + (userId == null ? "空" : userId) + "，昵称:" + (nickName == null ? "空" : nickName) + "，点赞时间:" + (createTime == null ? "空" : createTime);
    }
}
