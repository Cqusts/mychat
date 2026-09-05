package com.mychat.service;

import com.mychat.entity.dto.TokenUserInfoDto;
import com.mychat.entity.po.UserContactApply;
import com.mychat.entity.query.UserContactApplyQuery;
import com.mychat.entity.vo.PaginationResultVO;
import com.mychat.entity.vo.UserContactApplyCursorVO;

import java.util.List;


/**
 * 联系人申请 业务接口
 */
public interface UserContactApplyService {

    /**
     * 根据条件查询列表
     */
    List<UserContactApply> findListByParam(UserContactApplyQuery param);

    /**
     * 根据条件查询列表
     */
    Integer findCountByParam(UserContactApplyQuery param);

    /**
     * 分页查询
     */
    PaginationResultVO<UserContactApply> findListByPage(UserContactApplyQuery param);

    /**
     * 新增
     */
    Integer add(UserContactApply bean);

    /**
     * 批量新增
     */
    Integer addBatch(List<UserContactApply> listBean);

    /**
     * 批量新增/修改
     */
    Integer addOrUpdateBatch(List<UserContactApply> listBean);

    /**
     * 多条件更新
     */
    Integer updateByParam(UserContactApply bean, UserContactApplyQuery param);

    /**
     * 多条件删除
     */
    Integer deleteByParam(UserContactApplyQuery param);

    /**
     * 根据ApplyId查询对象
     */
    UserContactApply getUserContactApplyByApplyId(Integer applyId);


    /**
     * 根据ApplyId修改
     */
    Integer updateUserContactApplyByApplyId(UserContactApply bean, Integer applyId);


    /**
     * 根据ApplyId删除
     */
    Integer deleteUserContactApplyByApplyId(Integer applyId);


    /**
     * 根据ApplyUserIdAndReceiveUserIdAndContactId查询对象
     */
    UserContactApply getUserContactApplyByApplyUserIdAndReceiveUserIdAndContactId(String applyUserId, String receiveUserId, String contactId);


    /**
     * 根据ApplyUserIdAndReceiveUserIdAndContactId修改
     */
    Integer updateUserContactApplyByApplyUserIdAndReceiveUserIdAndContactId(UserContactApply bean, String applyUserId, String receiveUserId, String contactId);


    /**
     * 根据ApplyUserIdAndReceiveUserIdAndContactId删除
     */
    Integer deleteUserContactApplyByApplyUserIdAndReceiveUserIdAndContactId(String applyUserId, String receiveUserId, String contactId);

    Integer applyAdd(TokenUserInfoDto tokenUserInfoDto, String contactId, String contactType, String applyInfo);

    void dealWithApply(String userId, Integer applyId, Integer status);

    /**
     * 游标分页查询好友申请列表，按申请时间倒序
     *
     * @param userId   接收人ID
     * @param cursor   游标，格式 applyTime_id，首屏传 null
     * @param pageSize 每页条数
     */
    UserContactApplyCursorVO loadApplyByCursor(String userId, String cursor, Integer pageSize);
}