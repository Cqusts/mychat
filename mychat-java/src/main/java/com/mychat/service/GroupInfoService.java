package com.mychat.service;

import com.mychat.entity.dto.TokenUserInfoDto;
import com.mychat.entity.enums.MessageTypeEnum;
import com.mychat.entity.po.GroupInfo;
import com.mychat.entity.query.GroupInfoQuery;
import com.mychat.entity.vo.PaginationResultVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;


/**
 * 业务接口
 */
public interface GroupInfoService {

    /**
     * 根据条件查询列表
     */
    List<GroupInfo> findListByParam(GroupInfoQuery param);

    /**
     * 根据条件查询列表
     */
    Integer findCountByParam(GroupInfoQuery param);

    /**
     * 分页查询
     */
    PaginationResultVO<GroupInfo> findListByPage(GroupInfoQuery param);

    /**
     * 新增
     */
    Integer add(GroupInfo bean);

    /**
     * 批量新增
     */
    Integer addBatch(List<GroupInfo> listBean);

    /**
     * 批量新增/修改
     */
    Integer addOrUpdateBatch(List<GroupInfo> listBean);

    /**
     * 多条件更新
     */
    Integer updateByParam(GroupInfo bean, GroupInfoQuery param);

    /**
     * 多条件删除
     */
    Integer deleteByParam(GroupInfoQuery param);

    /**
     * 根据GroupId查询对象
     */
    GroupInfo getGroupInfoByGroupId(String groupId);


    /**
     * 根据GroupId修改
     */
    Integer updateGroupInfoByGroupId(GroupInfo bean, String groupId);


    /**
     * 根据GroupId删除
     */
    Integer deleteGroupInfoByGroupId(String groupId);

    void saveGroup(GroupInfo groupInfo, MultipartFile avatarFile, MultipartFile avatarCover);

    void dissolutionGroup(String userId, String groupId);

    void leaveGroup(String userId, String groupId, MessageTypeEnum messageTypeEnum);

    void addOrRemoveGroupUser(TokenUserInfoDto tokenUserInfoDto, String groupId, String contactIds, Integer opType);
}