package com.mychat.controller;

import com.mychat.annotation.GlobalInterceptor;
import com.mychat.entity.dto.TokenUserInfoDto;
import com.mychat.entity.dto.UserContactSearchResultDto;
import com.mychat.entity.enums.PageSize;
import com.mychat.entity.enums.ResponseCodeEnum;
import com.mychat.entity.enums.UserContactStatusEnum;
import com.mychat.entity.enums.UserContactTypeEnum;
import com.mychat.entity.po.UserContact;
import com.mychat.entity.po.UserInfo;
import com.mychat.entity.query.UserContactApplyQuery;
import com.mychat.entity.query.UserContactQuery;
import com.mychat.entity.vo.PaginationResultVO;
import com.mychat.ai.AiAgentDefinition;
import com.mychat.ai.AiAgentRegistry;
import com.mychat.entity.vo.AiAgentVO;
import com.mychat.entity.vo.ResponseVO;
import com.mychat.entity.vo.UserInfoVO;
import com.mychat.exception.BusinessException;
import com.mychat.redis.RedisUtils;
import com.mychat.service.GroupInfoService;
import com.mychat.service.UserContactApplyService;
import com.mychat.service.UserContactService;
import com.mychat.service.UserInfoService;
import com.mychat.utils.CopyTools;
import jodd.util.ArraysUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/contact")
public class UserContactController extends ABaseController {
    private static final Logger logger = LoggerFactory.getLogger(UserContactController.class);

    @Resource
    private UserInfoService userInfoService;

    @Resource
    private AiAgentRegistry aiAgentRegistry;

    @Resource
    private GroupInfoService groupInfoService;

    @Resource
    private RedisUtils redisUtils;

    @Resource
    private UserContactService userContactService;

    @Resource
    private UserContactApplyService userContactApplyService;

    @RequestMapping("/search")
    @GlobalInterceptor
    public ResponseVO search(HttpServletRequest request, @NotEmpty String contactId) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        UserContactSearchResultDto resultDto = userContactService.searchContact(tokenUserInfoDto.getUserId(), contactId);
        return getSuccessResponseVO(resultDto);
    }

    /**
     * 列出系统里所有的AI助手。
     * 助手不是任何人的好友，也不会出现在搜索推荐里，需要单独给一个列表让用户发现它们。
     * 拿到ID之后走的还是普通的加好友流程——助手的joinType是"直接加入"，不需要审批。
     */
    @RequestMapping("/loadAiAgents")
    @GlobalInterceptor
    public ResponseVO loadAiAgents(HttpServletRequest request) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        //一次性查出当前用户已经加了哪些助手，避免每个助手查一次库
        Set<String> myContactIds = new HashSet<>();
        UserContactQuery contactQuery = new UserContactQuery();
        contactQuery.setUserId(tokenUserInfoDto.getUserId());
        contactQuery.setContactType(UserContactTypeEnum.USER.getType());
        contactQuery.setStatus(UserContactStatusEnum.FRIEND.getStatus());
        List<UserContact> myContacts = userContactService.findListByParam(contactQuery);
        if (myContacts != null) {
            for (UserContact contact : myContacts) {
                myContactIds.add(contact.getContactId());
            }
        }

        List<AiAgentVO> agentList = new ArrayList<>();
        for (AiAgentDefinition agent : aiAgentRegistry.getAgents()) {
            AiAgentVO vo = new AiAgentVO();
            vo.setContactId(agent.getId());
            vo.setContactName(agent.getName());
            vo.setSignature(agent.getSignature());
            vo.setDescription(agent.getDescription());
            vo.setInContact(myContactIds.contains(agent.getId()));
            agentList.add(vo);
        }
        return getSuccessResponseVO(agentList);
    }

    @RequestMapping("/applyAdd")
    @GlobalInterceptor
    public ResponseVO applyAdd(HttpServletRequest request, @NotEmpty String contactId,
                               @NotEmpty String contactType, String applyInfo) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        Integer joinType = userContactApplyService.applyAdd(tokenUserInfoDto, contactId, contactType, applyInfo);
        return getSuccessResponseVO(joinType);
    }

    @RequestMapping("/loadApply")
    @GlobalInterceptor
    public ResponseVO loadApply(HttpServletRequest request, Integer pageNo) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        UserContactApplyQuery userContactApplyQuery = new UserContactApplyQuery();
        userContactApplyQuery.setOrderBy("last_apply_time desc");
        userContactApplyQuery.setReceiveUserId(tokenUserInfoDto.getUserId());
        userContactApplyQuery.setQueryContactInfo(true);
        userContactApplyQuery.setPageNo(pageNo);
        userContactApplyQuery.setPageSize(PageSize.SIZE15.getSize());
        PaginationResultVO resultVO = userContactApplyService.findListByPage(userContactApplyQuery);
        return getSuccessResponseVO(resultVO);
    }

    @RequestMapping("/dealWithApply")
    @GlobalInterceptor
    public ResponseVO dealWithApply(HttpServletRequest request, @NotNull Integer applyId, @NotNull Integer status) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        userContactApplyService.dealWithApply(tokenUserInfoDto.getUserId(), applyId, status);
        return getSuccessResponseVO(null);
    }

    @RequestMapping("/loadContact")
    @GlobalInterceptor
    public ResponseVO loadContact(HttpServletRequest request, @NotEmpty String contactType) {
        UserContactTypeEnum contactTypeEnum = UserContactTypeEnum.getByName(contactType);
        if (null == contactTypeEnum) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        UserContactQuery contactQuery = new UserContactQuery();
        contactQuery.setUserId(tokenUserInfoDto.getUserId());
        contactQuery.setContactType(contactTypeEnum.getType());
        if (UserContactTypeEnum.USER == contactTypeEnum) {
            contactQuery.setQueryContactUserInfo(true);
        } else if (UserContactTypeEnum.GROUP == contactTypeEnum) {
            contactQuery.setQueryGroupInfo(true);
            contactQuery.setExcludeMyGroup(true);
        }
        contactQuery.setStatusArray(new Integer[]{
                UserContactStatusEnum.FRIEND.getStatus(),
                UserContactStatusEnum.DEL_BE.getStatus(),
                UserContactStatusEnum.BLACKLIST_BE.getStatus()});
        contactQuery.setOrderBy("last_update_time desc");
        List<UserContact> contactList = userContactService.findListByParam(contactQuery);
        return getSuccessResponseVO(contactList);
    }


    @RequestMapping("/getContactInfo")
    @GlobalInterceptor
    public ResponseVO getContactInfo(HttpServletRequest request, @NotEmpty String contactId) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        UserInfo userInfo = userInfoService.getUserInfoByUserId(contactId);
        UserInfoVO userInfoVO = CopyTools.copy(userInfo, UserInfoVO.class);
        userInfoVO.setContactStatus(UserContactStatusEnum.NOT_FRIEND.getStatus());
        //判断是否是联系人
        UserContact userContact = userContactService.getUserContactByUserIdAndContactId(tokenUserInfoDto.getUserId(), contactId);
        if (userContact != null) {
            userInfoVO.setContactStatus(userContact.getStatus());
        }
        return getSuccessResponseVO(userInfoVO);
    }

    @RequestMapping("/getContactUserInfo")
    @GlobalInterceptor
    public ResponseVO getContactUserInfo(HttpServletRequest request, @NotEmpty String contactId) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        UserContact userContact = this.userContactService.getUserContactByUserIdAndContactId(tokenUserInfoDto.getUserId(), contactId);
        if (null == userContact || !ArraysUtil.contains(new Integer[]{
                UserContactStatusEnum.FRIEND.getStatus(),
                UserContactStatusEnum.DEL_BE.getStatus(),
                UserContactStatusEnum.BLACKLIST_BE.getStatus(),
                UserContactStatusEnum.BLACKLIST_BE_FIRST.getStatus()}, userContact.getStatus())) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        UserInfo userInfo = userInfoService.getUserInfoByUserId(contactId);
        UserInfoVO userInfoVO = CopyTools.copy(userInfo, UserInfoVO.class);
        return getSuccessResponseVO(userInfoVO);
    }


    /**
     * 删除联系人
     *
     * @param request
     * @param contactId
     * @return
     */
    @RequestMapping("/delContact")
    @GlobalInterceptor
    public ResponseVO delContact(HttpServletRequest request, @NotEmpty String contactId) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        userContactService.removeUserContact(tokenUserInfoDto.getUserId(), contactId, UserContactStatusEnum.DEL);
        return getSuccessResponseVO(null);
    }

    /**
     * 添加到黑名单
     *
     * @param request
     * @param contactId
     * @return
     */
    @RequestMapping("/addContact2BlackList")
    @GlobalInterceptor
    public ResponseVO addContact2BlackList(HttpServletRequest request, @NotEmpty String contactId) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        userContactService.removeUserContact(tokenUserInfoDto.getUserId(), contactId, UserContactStatusEnum.BLACKLIST);
        return getSuccessResponseVO(null);
    }
}
