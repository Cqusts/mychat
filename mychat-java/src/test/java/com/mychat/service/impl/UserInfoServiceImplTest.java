package com.mychat.service.impl;

import com.mychat.entity.config.AppConfig;
import com.mychat.entity.enums.UserContactStatusEnum;
import com.mychat.entity.po.UserContact;
import com.mychat.entity.po.UserInfo;
import com.mychat.entity.query.UserContactQuery;
import com.mychat.entity.query.UserInfoQuery;
import com.mychat.entity.vo.UserInfoVO;
import com.mychat.mappers.UserContactMapper;
import com.mychat.mappers.UserInfoMapper;
import com.mychat.redis.RedisComponet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 针对「UserInfo 增加 lastActiveTime 字段，登录时更新它」改动
 * 的单元测试。只测 login 里新增的 lastActiveTime 更新逻辑，
 * 不依赖真实 MySQL / Redis，全部用 Mockito 打桩。
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

    private UserInfoServiceImpl userInfoServiceImpl;

    private UserInfo dbUser;

    @BeforeEach
    void setUp() {
        userInfoServiceImpl = new UserInfoServiceImpl();
        ReflectionTestUtils.setField(userInfoServiceImpl, "userInfoMapper", userInfoMapper);
        ReflectionTestUtils.setField(userInfoServiceImpl, "appConfig", appConfig);
        ReflectionTestUtils.setField(userInfoServiceImpl, "userContactMapper", userContactMapper);
        ReflectionTestUtils.setField(userInfoServiceImpl, "redisComponet", redisComponet);

        dbUser = new UserInfo();
        dbUser.setUserId("U10001");
        dbUser.setEmail("test@example.com");
        dbUser.setNickName("测试用户");
        dbUser.setPassword("123456"); // login 里直接与传入明文比对
        dbUser.setStatus(UserContactStatusEnum.FRIEND.getStatus());
        dbUser.setLastLoginTime(new Date());
        dbUser.setLastOffTime(0L);
    }

    /**
     * 正常登录：应调用 updateByUserId 且传入 bean 的 lastActiveTime 为当前时间（非空）。
     */
    @Test
    void 登录成功会调用updateByUserId并携带当前lastActiveTime() {
        when(userInfoMapper.selectByEmail("test@example.com")).thenReturn(dbUser);
        when(appConfig.getAdminEmails()).thenReturn("");
        when(userContactMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(redisComponet.getUserHeartBeat("U10001")).thenReturn(null);

        UserInfoVO result = userInfoServiceImpl.login("test@example.com", "123456");

        assertNotNull(result, "登录应返回VO");
        assertEquals("U10001", result.getUserId());

        // 捕获传给 updateByUserId 的 bean，校验 lastActiveTime 被设置
        ArgumentCaptor<UserInfo> captor = ArgumentCaptor.forClass(UserInfo.class);
        verify(userInfoMapper).updateByUserId(captor.capture(), anyString());
        UserInfo updateBean = captor.getValue();
        assertNotNull(updateBean.getLastActiveTime(), "登录更新时应设置 lastActiveTime");
        long diff = Math.abs(System.currentTimeMillis() - updateBean.getLastActiveTime().getTime());
        assertTrue(diff < 5000, "lastActiveTime 应接近当前时间，实际偏差 " + diff + "ms");
    }

    /**
     * 更新 lastActiveTime 抛异常时，不应阻断登录，login 仍应正常返回 VO。
     */
    @Test
    void 更新lastActiveTime失败不阻断登录() {
        when(userInfoMapper.selectByEmail("test@example.com")).thenReturn(dbUser);
        when(appConfig.getAdminEmails()).thenReturn("");
        when(userContactMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(redisComponet.getUserHeartBeat("U10001")).thenReturn(null);
        doThrow(new RuntimeException("DB down")).when(userInfoMapper).updateByUserId(any(), anyString());

        UserInfoVO result = userInfoServiceImpl.login("test@example.com", "123456");

        // 更新失败被吞掉，登录仍成功返回 VO
        assertNotNull(result, "lastActiveTime 更新失败不应阻断登录");
        assertEquals("U10001", result.getUserId());
    }

    /**
     * 账号已禁用时不应走到 lastActiveTime 更新逻辑。
     */
    @Test
    void 账号禁用登录失败不更新lastActiveTime() {
        dbUser.setStatus(0); // 禁用
        when(userInfoMapper.selectByEmail("test@example.com")).thenReturn(dbUser);

        try {
            userInfoServiceImpl.login("test@example.com", "123456");
        } catch (Exception ignored) {
        }

        verify(userInfoMapper, never()).updateByUserId(any(), anyString());
    }

    /**
     * 密码错误时不应走到 lastActiveTime 更新逻辑。
     */
    @Test
    void 密码错误登录失败不更新lastActiveTime() {
        when(userInfoMapper.selectByEmail("test@example.com")).thenReturn(dbUser);

        try {
            userInfoServiceImpl.login("test@example.com", "wrong-password");
        } catch (Exception ignored) {
        }

        verify(userInfoMapper, never()).updateByUserId(any(), anyString());
    }

    /**
     * UserInfo 的 lastActiveTime 字段带 @JsonIgnore，不对外序列化。
     * 这里校验该注解存在，防止后续被误删导致字段泄漏到前端。
     */
    @Test
    void lastActiveTime字段带JsonIgnore注解不对外暴露() throws Exception {
        java.lang.reflect.Field field = UserInfo.class.getDeclaredField("lastActiveTime");
        com.fasterxml.jackson.annotation.JsonIgnore ignore = field.getAnnotation(com.fasterxml.jackson.annotation.JsonIgnore.class);
        assertNotNull(ignore, "lastActiveTime 必须带 @JsonIgnore 注解，避免序列化泄漏");
    }

    /**
     * UserInfo.getOnlineType 依赖 lastLoginTime/lastOffTime，不受 lastActiveTime 影响，
     * 确认新增字段不破坏既有在线状态判断逻辑。
     */
    @Test
    void 新增lastActiveTime不影响onlineType判断() {
        UserInfo u = new UserInfo();
        u.setLastOffTime(0L);
        u.setLastLoginTime(new Date(System.currentTimeMillis() - 1000));
        assertEquals(1, u.getOnlineType(), "最近有登录记录应判为在线");
    }
}
