package com.easychat.entity.query;


/**
 * 朋友圈点赞表参数
 */
public class UserMomentsLikeQuery extends BaseParam {


    /**
     * 动态ID
     */
    private Long momentsId;

    /**
     * 点赞用户ID
     */
    private String userId;

    private String userIdFuzzy;

    /**
     * 点赞用户昵称
     */
    private String nickName;

    private String nickNameFuzzy;

    /**
     * 点赞时间
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

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    public Long getCreateTime() {
        return this.createTime;
    }
}
