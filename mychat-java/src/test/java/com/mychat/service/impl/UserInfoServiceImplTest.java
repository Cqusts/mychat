package com.mychat.service.impl;

import com.mychat.entity.config.AppConfig;
import com.mychat.entity.enums.UserStatusEnum;
import com.mychat.entity.po.UserContact;
import com.mychat.entity.po.UserInfo;
import com.mychat.entity.query.UserContactQuery;
import com.mychat.entity.query.UserInfoQuery;
import com.mychat.entity.vo.UserInfoVO;
import com.mychat.exception.BusinessException;
import com.mychat.mappers.UserContactMapper;
import com.mychat.mappers.UserInfoMapper;
import com.mychat.redis.RedisComponet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录时更新 lastActiveTime 逻辑的单元测试。
 * login 依赖大量外部组件（Redis、联系人Mapper等），全部用 Mockito 打桩，
 * 不依赖真实 MySQL / Redis / Spring 容器。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserInfoServiceImplTest {

    @Mock
    private UserInfoMapper<UserInfo, UserInfoQuery> userInfoMapper;

    @Mock
    private UserContactMapper<UserContact, UserContactQuery> userContactMapper;

    @Mock
    private RedisComponet redisComponet;

    @Mock
    private AppConfig appConfig;

    @InjectMocks
    private UserInfoServiceImpl userInfoService;

    private static final String EMAIL = "test@example.com";
    private static final String PASSWORD = "123456";
    private static final String USER_ID = "U12345678901";

    private UserInfo buildNormalUser() {
        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(USER_ID);
        userInfo.setEmail(EMAIL);
        userInfo.setNickName("测试用户");
        userInfo.setPassword(PASSWORD);
        userInfo.setStatus(UserStatusEnum.ENABLE.getStatus());
        return userInfo;
    }

    /**
     * 打桩出「登录前置条件全部满足」的默认环境：
     * 用户存在、密码对、状态启用、无好友、不在别处登录、非管理员。
     */
    private void stubLoginPrerequisites(UserInfo userInfo) {
        when(userInfoMapper.selectByEmail(EMAIL)).thenReturn(userInfo);
        when(userContactMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(redisComponet.getUserHeartBeat(anyString())).thenReturn(null);
        when(appConfig.getAdminEmails()).thenReturn(null);
    }

    @Test
    void 登录成功会调用updateLastActiveTime更新活跃时间() {
        UserInfo userInfo = buildNormalUser();
        stubLoginPrerequisites(userInfo);

        UserInfoVO vo = userInfoService.login(EMAIL, PASSWORD);

        assertNotNull(vo);
        assertEquals(USER_ID, vo.getUserId());
        // 关键断言：登录成功必须触发活跃时间更新
        verify(userInfoMapper, times(1)).updateLastActiveTime(USER_ID);
    }

    @Test
    void updateLastActiveTime抛异常时登录仍成功且不向上抛() {
        UserInfo userInfo = buildNormalUser();
        stubLoginPrerequisites(userInfo);
        // 独立事务更新失败，仅记日志，不应影响主登录
        doThrow(new RuntimeException("模拟数据库连接失败")).when(userInfoMapper).updateLastActiveTime(anyString());

        // 登录本身不应抛异常
        UserInfoVO vo = userInfoService.login(EMAIL, PASSWORD);

        assertNotNull(vo);
        assertEquals(USER_ID, vo.getUserId());
        verify(userInfoMapper, times(1)).updateLastActiveTime(USER_ID);
    }

    @Test
    void 密码错误时不更新最后活跃时间() {
        UserInfo userInfo = buildNormalUser();
        when(userInfoMapper.selectByEmail(EMAIL)).thenReturn(userInfo);

        assertThrows(BusinessException.class, () -> userInfoService.login(EMAIL, "wrong-password"));

        verify(userInfoMapper, never()).updateLastActiveTime(anyString());
    }

    @Test
    void 账号禁用时不更新最后活跃时间() {
        UserInfo userInfo = buildNormalUser();
        userInfo.setStatus(UserStatusEnum.DISABLE.getStatus());
        when(userInfoMapper.selectByEmail(EMAIL)).thenReturn(userInfo);

        assertThrows(BusinessException.class, () -> userInfoService.login(EMAIL, PASSWORD));

        verify(userInfoMapper, never()).updateLastActiveTime(anyString());
    }

    @Test
    void 已在别处登录时不更新最后活跃时间() {
        UserInfo userInfo = buildNormalUser();
        when(userInfoMapper.selectByEmail(EMAIL)).thenReturn(userInfo);
        when(userContactMapper.selectList(any())).thenReturn(new ArrayList<>());
        // 心跳存在代表该账号已在别处登录，应直接拦截
        when(redisComponet.getUserHeartBeat(anyString())).thenReturn(System.currentTimeMillis());
        when(appConfig.getAdminEmails()).thenReturn(null);

        assertThrows(BusinessException.class, () -> userInfoService.login(EMAIL, PASSWORD));

        verify(userInfoMapper, never()).updateLastActiveTime(anyString());
    }

    @Test
    void 用户不存在时不更新最后活跃时间() {
        when(userInfoMapper.selectByEmail(EMAIL)).thenReturn(null);

        assertThrows(BusinessException.class, () -> userInfoService.login(EMAIL, PASSWORD));

        verify(userInfoMapper, never()).updateLastActiveTime(anyString());
    }

    @Test
    void 有好友时登录成功也更新活跃时间() {
        UserInfo userInfo = buildNormalUser();
        when(userInfoMapper.selectByEmail(EMAIL)).thenReturn(userInfo);
        List<UserContact> contacts = new ArrayList<>();
        UserContact contact = new UserContact();
        contact.setUserId(USER_ID);
        contact.setContactId("U99999999999");
        contacts.add(contact);
        when(userContactMapper.selectList(any())).thenReturn(contacts);
        when(redisComponet.getUserHeartBeat(anyString())).thenReturn(null);
        when(appConfig.getAdminEmails()).thenReturn(null);

        UserInfoVO vo = userInfoService.login(EMAIL, PASSWORD);

        assertNotNull(vo);
        verify(userInfoMapper, times(1)).updateLastActiveTime(USER_ID);
    }
}
