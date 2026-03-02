package com.easychat.entity.po;

import java.io.Serializable;
import java.util.List;


/**
 * 朋友圈动态表
 */
public class UserMoments implements Serializable {


    /**
     * 动态ID
     */
    private Long momentsId;

    /**
     * 发布用户ID
     */
    private String userId;

    /**
     * 发布用户昵称
     */
    private String nickName;

    /**
     * 文字内容
     */
    private String content;

    /**
     * 图片列表(逗号分隔)
     */
    private String images;

    /**
     * 动态类型 0:纯文字 1:图文 2:视频
     */
    private Integer type;

    /**
     * 点赞数
     */
    private Integer likeCount;

    /**
     * 评论数
     */
    private Integer commentCount;

    /**
     * 发布时间(毫秒时间戳)
     */
    private Long createTime;

    /**
     * 状态 0:已删除 1:正常
     */
    private Integer status;

    /**
     * 当前用户是否已点赞(非数据库字段)
     */
    private Boolean liked;

    /**
     * 评论列表(非数据库字段)
     */
    private List<UserMomentsComment> comments;

    /**
     * 点赞用户列表(非数据库字段)
     */
    private List<UserMomentsLike> likes;


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

    public void setContent(String content) {
        this.content = content;
    }

    public String getContent() {
        return this.content;
    }

    public void setImages(String images) {
        this.images = images;
    }

    public String getImages() {
        return this.images;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public Integer getType() {
        return this.type;
    }

    public void setLikeCount(Integer likeCount) {
        this.likeCount = likeCount;
    }

    public Integer getLikeCount() {
        return this.likeCount;
    }

    public void setCommentCount(Integer commentCount) {
        this.commentCount = commentCount;
    }

    public Integer getCommentCount() {
        return this.commentCount;
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

    public Boolean getLiked() {
        return liked;
    }

    public void setLiked(Boolean liked) {
        this.liked = liked;
    }

    public List<UserMomentsComment> getComments() {
        return comments;
    }

    public void setComments(List<UserMomentsComment> comments) {
        this.comments = comments;
    }

    public List<UserMomentsLike> getLikes() {
        return likes;
    }

    public void setLikes(List<UserMomentsLike> likes) {
        this.likes = likes;
    }

    @Override
    public String toString() {
        return "动态ID:" + (momentsId == null ? "空" : momentsId) + "，用户ID:" + (userId == null ? "空" : userId) + "，昵称:" + (nickName == null ? "空" : nickName) + "，内容:" + (content == null ? "空" : content) + "，类型:" + (type == null ? "空" : type) + "，点赞数:" + (likeCount == null ? "空" : likeCount) + "，评论数:" + (commentCount == null ? "空" : commentCount) + "，发布时间:" + (createTime == null ? "空" : createTime) + "，状态:" + (status == null ? "空" : status);
    }
}
