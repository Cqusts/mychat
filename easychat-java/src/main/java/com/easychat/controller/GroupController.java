package com.easychat.controller;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.GroupStatusEnum;
import com.easychat.entity.enums.MessageTypeEnum;
import com.easychat.entity.enums.UserContactStatusEnum;
import com.easychat.entity.po.GroupInfo;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.query.GroupInfoQuery;
import com.easychat.entity.query.UserContactQuery;
import com.easychat.entity.vo.GroupInfoVO;
import com.easychat.ai.AiAgentDefinition;
import com.easychat.ai.AiAgentRegistry;
import com.easychat.entity.vo.AiAgentVO;
import com.easychat.entity.vo.ResponseVO;
import com.easychat.exception.BusinessException;
import com.easychat.service.GroupInfoService;
import com.easychat.service.UserContactService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;

/**
 * @ClassName GroupController
 * @Description TODO
 * @Author 程序员老罗 https://space.bilibili.com/499388891
 * @Date 2023/12/24 20:04
 */
@RestController("groupController")
@RequestMapping("/group")
public class GroupController extends ABaseController {

    @Resource
    private GroupInfoService groupInfoService;

    @Resource
    private AiAgentRegistry aiAgentRegistry;

    @Resource
    private UserContactService userContactService;

    @RequestMapping(value = "/saveGroup")
    @GlobalInterceptor
    public ResponseVO saveGroup(HttpServletRequest request,
                                String groupId,
                                @NotEmpty String groupName,
                                String groupNotice,
                                @NotNull Integer joinType,
                                MultipartFile avatarFile,
                                MultipartFile avatarCover) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        GroupInfo groupInfo = new GroupInfo();
        groupInfo.setGroupId(groupId);
        groupInfo.setGroupOwnerId(tokenUserInfoDto.getUserId());
        groupInfo.setGroupName(groupName);
        groupInfo.setGroupNotice(groupNotice);
        groupInfo.setJoinType(joinType);
        this.groupInfoService.saveGroup(groupInfo, avatarFile, avatarCover);
        return getSuccessResponseVO(null);
    }

    @RequestMapping(value = "/loadMyGroup")
    @GlobalInterceptor
    public ResponseVO loadMyGroup(HttpServletRequest request) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        GroupInfoQuery infoQuery = new GroupInfoQuery();
        infoQuery.setGroupOwnerId(tokenUserInfoDto.getUserId());
        infoQuery.setOrderBy("create_time desc");
	infoQuery.setStatus(GroupStatusEnum.NORMAL.getStatus());
        List<GroupInfo> groupInfoList = this.groupInfoService.findListByParam(infoQuery);
        return getSuccessResponseVO(groupInfoList);
    }

    /**
     * 获取群信息
     *
     * @param request
     * @param groupId
     * @return
     */
    @RequestMapping(value = "/getGroupInfo")
    @GlobalInterceptor
    public ResponseVO getGroupInfo(HttpServletRequest request,
                                   @NotEmpty String groupId) {
        GroupInfo groupInfo = getGroupDetailCommon(request, groupId);
        UserContactQuery userContactQuery = new UserContactQuery();
        userContactQuery.setContactId(groupId);
        Integer memberCount = this.userContactService.findCountByParam(userContactQuery);
        groupInfo.setMemberCount(memberCount);
        return getSuccessResponseVO(groupInfo);
    }

    private GroupInfo getGroupDetailCommon(HttpServletRequest request, String groupId) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        UserContact userContact = this.userContactService.getUserContactByUserIdAndContactId(tokenUserInfoDto.getUserId(), groupId);
        if (userContact == null || !UserContactStatusEnum.FRIEND.getStatus().equals(userContact.getStatus())) {
            throw new BusinessException("你不在群聊或者群聊不存在或已经解散");
        }
        GroupInfo groupInfo = this.groupInfoService.getGroupInfoByGroupId(groupId);
        if (groupInfo == null || !GroupStatusEnum.NORMAL.getStatus().equals(groupInfo.getStatus())) {
            throw new BusinessException("群聊不存在或已经解散");
        }
        return groupInfo;
    }

    @RequestMapping(value = "/getGroupInfo4Chat")
    @GlobalInterceptor
    public ResponseVO getGroupInfo4Chat(HttpServletRequest request, @NotEmpty String groupId) {
        GroupInfo groupInfo = getGroupDetailCommon(request, groupId);
        UserContactQuery userContactQuery = new UserContactQuery();
        userContactQuery.setContactId(groupId);
        userContactQuery.setQueryUserInfo(true);
        userContactQuery.setOrderBy("create_time asc");
        userContactQuery.setStatus(UserContactStatusEnum.FRIEND.getStatus());
        List<UserContact> userContactList = this.userContactService.findListByParam(userContactQuery);
        GroupInfoVO groupInfoVo = new GroupInfoVO();
        groupInfoVo.setGroupInfo(groupInfo);
        groupInfoVo.setUserContactList(userContactList);
        return getSuccessResponseVO(groupInfoVo);
    }


    /**
     * 退群
     *
     * @param request
     * @param groupId
     * @return
     */
    @RequestMapping(value = "/leaveGroup")
    @GlobalInterceptor
    public ResponseVO leaveGroup(HttpServletRequest request, @NotEmpty String groupId) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        groupInfoService.leaveGroup(tokenUserInfoDto.getUserId(), groupId, MessageTypeEnum.LEAVE_GROUP);
        return getSuccessResponseVO(null);
    }

    /**
     * 解散群
     *
     * @param request
     * @param groupId
     * @return
     */
    @RequestMapping(value = "/dissolutionGroup")
    @GlobalInterceptor
    public ResponseVO dissolutionGroup(HttpServletRequest request, @NotEmpty String groupId) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        groupInfoService.dissolutionGroup(tokenUserInfoDto.getUserId(), groupId);
        return getSuccessResponseVO(null);
    }


    /**
     * 添加或者移除人员
     *
     * @param request
     * @param groupId
     * @param selectContacts
     * @param opType
     * @return
     */
    /**
     * 列出所有可以拉进群的AI助手。
     * 助手不是任何人的好友，不会出现在通讯录里，所以要单独给一个列表。
     * 拿到之后走的还是addOrRemoveGroupUser那条路——助手在系统里就是普通用户。
     */
    @RequestMapping(value = "/loadAiAgents")
    @GlobalInterceptor
    public ResponseVO loadAiAgents(HttpServletRequest request, @NotEmpty String groupId) {
        //复用群成员校验：不在群里的人看不到
        getGroupDetailCommon(request, groupId);
        List<AiAgentVO> agentList = new ArrayList<>();
        for (AiAgentDefinition agent : aiAgentRegistry.getAgents()) {
            AiAgentVO vo = new AiAgentVO();
            vo.setContactId(agent.getId());
            vo.setContactName(agent.getName());
            vo.setSignature(agent.getSignature());
            agentList.add(vo);
        }
        return getSuccessResponseVO(agentList);
    }

    @RequestMapping(value = "/addOrRemoveGroupUser")
    @GlobalInterceptor
    public ResponseVO addOrRemoveGroupUser(HttpServletRequest request, @NotEmpty String groupId, @NotEmpty String selectContacts,
                                           @NotNull Integer opType) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        groupInfoService.addOrRemoveGroupUser(tokenUserInfoDto, groupId, selectContacts, opType);
        return getSuccessResponseVO(null);
    }
}
