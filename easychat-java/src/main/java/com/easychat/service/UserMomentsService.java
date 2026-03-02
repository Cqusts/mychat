package com.easychat.service;

import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.po.UserMoments;
import com.easychat.entity.query.UserMomentsQuery;
import com.easychat.entity.vo.PaginationResultVO;

import java.util.List;


/**
 * 朋友圈动态表 业务接口
 */
public interface UserMomentsService {

    /**
     * 根据条件查询列表
     */
    List<UserMoments> findListByParam(UserMomentsQuery param);

    /**
     * 根据条件查询列表
     */
    Integer findCountByParam(UserMomentsQuery param);

    /**
     * 分页查询
     */
    PaginationResultVO<UserMoments> findListByPage(UserMomentsQuery param);

    /**
     * 新增
     */
    Integer add(UserMoments bean);

    /**
     * 批量新增
     */
    Integer addBatch(List<UserMoments> listBean);

    /**
     * 批量新增/修改
     */
    Integer addOrUpdateBatch(List<UserMoments> listBean);

    /**
     * 多条件更新
     */
    Integer updateByParam(UserMoments bean, UserMomentsQuery param);

    /**
     * 多条件删除
     */
    Integer deleteByParam(UserMomentsQuery param);

    /**
     * 根据MomentsId查询对象
     */
    UserMoments getUserMomentsByMomentsId(Long momentsId);


    /**
     * 根据MomentsId修改
     */
    Integer updateUserMomentsByMomentsId(UserMoments bean, Long momentsId);


    /**
     * 根据MomentsId删除
     */
    Integer deleteUserMomentsByMomentsId(Long momentsId);

    /**
     * 发布朋友圈动态
     */
    UserMoments publishMoments(TokenUserInfoDto tokenUserInfoDto, String content, String images, Integer type);

    /**
     * 获取朋友圈动态流(好友+自己的动态)
     */
    PaginationResultVO<UserMoments> loadMomentsFeed(TokenUserInfoDto tokenUserInfoDto, Integer pageNo);

    /**
     * 获取指定用户的动态列表
     */
    PaginationResultVO<UserMoments> loadUserMoments(String userId, String currentUserId, Integer pageNo);

    /**
     * 删除自己的动态
     */
    void deleteMoments(String userId, Long momentsId);

    /**
     * 点赞/取消点赞
     */
    void likeMoments(TokenUserInfoDto tokenUserInfoDto, Long momentsId);

    /**
     * 评论动态
     */
    void commentMoments(TokenUserInfoDto tokenUserInfoDto, Long momentsId, String content, String replyUserId);

    /**
     * 获取动态详情(包含评论和点赞列表)
     */
    UserMoments getMomentsDetail(Long momentsId, String currentUserId);
}
