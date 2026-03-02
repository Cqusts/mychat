package com.easychat.entity.query;


import java.util.List;

/**
 * 朋友圈动态表参数
 */
public class UserMomentsQuery extends BaseParam {


    /**
     * 动态ID
     */
    private Long momentsId;

    /**
     * 发布用户ID
     */
    private String userId;

    private String userIdFuzzy;

    /**
     * 发布用户昵称
     */
    private String nickName;

    private String nickNameFuzzy;

    /**
     * 文字内容
     */
    private String content;

    private String contentFuzzy;

    /**
     * 图片列表
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
     * 发布时间
     */
    private Long createTime;

    /**
     * 状态 0:已删除 1:正常
     */
    private Integer status;

    /**
     * 好友ID列表(用于查询朋友圈动态流)
     */
    private List<String> userIdList;

    /**
     * 当前查看用户ID(用于查是否点赞)
     */
    private String currentUserId;


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

    public List<String> getUserIdList() {
        return userIdList;
    }

    public void setUserIdList(List<String> userIdList) {
        this.userIdList = userIdList;
    }

    public String getCurrentUserId() {
        return currentUserId;
    }

    public void setCurrentUserId(String currentUserId) {
        this.currentUserId = currentUserId;
    }
}
