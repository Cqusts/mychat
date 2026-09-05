package com.mychat.service.impl;

import com.mychat.entity.config.AppConfig;
import com.mychat.entity.constants.Constants;
import com.mychat.entity.dto.TokenUserInfoDto;
import com.mychat.entity.enums.UserContactStatusEnum;
import com.mychat.entity.enums.UserStatusEnum;
import com.mychat.entity.po.UserContact;
import com.mychat.entity.po.UserInfo;
import com.mychat.entity.query.UserContactQuery;
import com.mychat.entity.query.UserInfoQuery;
import com.mychat.entity.vo.UserInfoVO;
import com.mychat.exception.BusinessException;
import com.mychat.mappers.UserContactMapper;
import com.mychat.mappers.UserInfoBeautyMapper;
import com.mychat.mappers.UserInfoMapper;
import com.mychat.redis.RedisComponet;
import com.mychat.service.ChatSessionUserService;
import com.mychat.service.UserContactService;
import com.mychat.websocket.MessageHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 针对「UserInfo 增加 lastActiveTime 字段，登录时更新」改动的单元测试。
 * 重点覆盖 login() 中调用 updateLastActiveTime 的行为：
 *  - 登录成功路径必须更新 lastActiveTime
 *  - 各类失败路径不得更新
 *  - 传参正确性（userId + 非空时间）
 */
@ExtendWith(MockitoExtension.class)
class UserInfoServiceImplTest {

    @Mock
    private UserInfoMapper<UserInfo, UserInfoQuery> userInfoMapper;
    @Mock
    private AppConfig appConfig;
    @Mock
    private UserContactMapper<UserContact, UserContactQuery> userContactMapper;
    @Mock
    private RedisComponet redisComponet;
    @Mock
    private ChatSessionUserService chatSessionUserService;
    @Mock
    private MessageHandler messageHandler;
    @Mock
    private UserContactService userContactService;
    @Mock
    private UserInfoBeautyMapper userInfoBeautyMapper;

    @InjectMocks
    private UserInfoServiceImpl userInfoService;

    private static final String EMAIL = "test@example.com";
    private static final String PASSWORD = "123456";
    private static final String USER_ID = "U12345678901";

    private UserInfo buildEnableUser() {
        UserInfo user = new UserInfo();
        user.setUserId(USER_ID);
        user.setEmail(EMAIL);
        user.setPassword(PASSWORD);
        user.setStatus(UserStatusEnum.ENABLE.getStatus());
        user.setNickName("测试用户");
        return user;
    }

    @BeforeEach
    void setUp() {
        // 默认 adminEmails 为空，避免 admin 判定干扰
        lenient().when(appConfig.getAdminEmails()).thenReturn("");
    }

    /**
     * 登录成功：必须调用 updateLastActiveTime，且 userId 正确、时间为当前非空值
     */
    @Test
    void 登录成功时更新lastActiveTime() {
        when(userInfoMapper.selectByEmail(EMAIL)).thenReturn(buildEnableUser());
        // 无好友
        when(userContactMapper.selectList(any())).thenReturn(new ArrayList<>());
        // 无心跳（未在别处登录）
        when(redisComponet.getUserHeartBeat(USER_ID)).thenReturn(null);

        UserInfoVO vo = userInfoService.login(EMAIL, PASSWORD);

        assertNotNull(vo);
        // 验证 updateLastActiveTime 被调用且参数正确
        ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Date> timeCaptor = ArgumentCaptor.forClass(Date.class);
        verify(userInfoMapper, times(1)).updateLastActiveTime(userIdCaptor.capture(), timeCaptor.capture());
        assertEquals(USER_ID, userIdCaptor.getValue());
        assertNotNull(timeCaptor.getValue(), "lastActiveTime 不能为 null");
        // 时间应在调用前后之间
        long now = System.currentTimeMillis();
        assertTrue(timeCaptor.getValue().getTime() <= now, "更新时间不应晚于当前时间");

        // 校验登录流程后的 redis 操作也正常发生
        verify(redisComponet, times(1)).saveTokenUserInfoDto(any(TokenUserInfoDto.class));
    }

    /**
     * 邮箱不存在时登录失败，不应调用 updateLastActiveTime
     */
    @Test
    void 邮箱不存在时不更新lastActiveTime() {
        when(userInfoMapper.selectByEmail(EMAIL)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userInfoService.login(EMAIL, PASSWORD));
        assertEquals("账号或者密码错误", ex.getMessage());
        verify(userInfoMapper, never()).updateLastActiveTime(anyString(), any(Date.class));
    }

    /**
     * 密码错误时登录失败，不应调用 updateLastActiveTime
     */
    @Test
    void 密码错误时不更新lastActiveTime() {
        when(userInfoMapper.selectByEmail(EMAIL)).thenReturn(buildEnableUser());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userInfoService.login(EMAIL, "wrongPassword"));
        assertEquals("账号或者密码错误", ex.getMessage());
        verify(userInfoMapper, never()).updateLastActiveTime(anyString(), any(Date.class));
    }

    /**
     * 账号被禁用时登录失败，不应调用 updateLastActiveTime
     */
    @Test
    void 账号禁用时不更新lastActiveTime() {
        UserInfo disabled = buildEnableUser();
        disabled.setStatus(UserStatusEnum.DISABLE.getStatus());
        when(userInfoMapper.selectByEmail(EMAIL)).thenReturn(disabled);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userInfoService.login(EMAIL, PASSWORD));
        assertEquals("账号已禁用", ex.getMessage());
        verify(userInfoMapper, never()).updateLastActiveTime(anyString(), any(Date.class));
    }

    /**
     * 已在别处登录（存在心跳）时登录失败，不应调用 updateLastActiveTime
     */
    @Test
    void 已在别处登录时不更新lastActiveTime() {
        when(userInfoMapper.selectByEmail(EMAIL)).thenReturn(buildEnableUser());
        when(userContactMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(redisComponet.getUserHeartBeat(USER_ID)).thenReturn(System.currentTimeMillis());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userInfoService.login(EMAIL, PASSWORD));
        assertEquals("此账号已经在别处登录，请退出后再登录", ex.getMessage());
        // 心跳校验在 updateLastActiveTime 之前，因此不应触发更新
        verify(userInfoMapper, never()).updateLastActiveTime(anyString(), any(Date.class));
    }

    /**
     * 登录成功且用户有关联好友时，也应正常更新 lastActiveTime
     */
    @Test
    void 登录成功带好友联系人也更新lastActiveTime() {
        when(userInfoMapper.selectByEmail(EMAIL)).thenReturn(buildEnableUser());

        UserContact friend = new UserContact();
        friend.setUserId(USER_ID);
        friend.setContactId("U00000000002");
        List<UserContact> friendList = new ArrayList<>();
        friendList.add(friend);
        when(userContactMapper.selectList(any())).thenReturn(friendList);

        when(redisComponet.getUserHeartBeat(USER_ID)).thenReturn(null);

        userInfoService.login(EMAIL, PASSWORD);

        verify(userInfoMapper, times(1)).updateLastActiveTime(eq(USER_ID), any(Date.class));
        // 好友列表写回 redis
        verify(redisComponet, times(1)).addUserContactBatch(eq(USER_ID), anyList());
    }

    /**
     * UserInfo 实体 lastActiveTime 字段的 getter/setter 存取正确
     */
    @Test
    void 实体lastActiveTime字段存取正确() {
        UserInfo user = new UserInfo();
        assertNull(user.getLastActiveTime(), "初始应为 null");

        Date d = new Date(System.currentTimeMillis() - 1000L);
        user.setLastActiveTime(d);
        assertEquals(d, user.getLastActiveTime());

        user.setLastActiveTime(null);
        assertNull(user.getLastActiveTime());
    }
}
