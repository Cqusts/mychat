package com.mychat.service.impl;

import com.mychat.entity.po.UserContactApply;
import com.mychat.entity.query.UserContactApplyQuery;
import com.mychat.mappers.UserContactApplyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 好友申请列表按申请时间倒序游标分页 —— 新增的 findApplyListByPage 链路的测试。
 *
 * 这次改动只在 query/mapper/service 三层新增了游标分页方法（controller 尚未接入），
 * 核心可测逻辑是：service 把 query 原样透传给 mapper，并原样返回结果。
 * 用 Mockito 打桩 mapper，不起 Spring 容器、不连数据库。
 */
@ExtendWith(MockitoExtension.class)
class UserContactApplyServiceImplTest {

    @Mock
    private UserContactApplyMapper<UserContactApply, UserContactApplyQuery> userContactApplyMapper;

    private UserContactApplyServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserContactApplyServiceImpl();
        ReflectionTestUtils.setField(service, "userContactApplyMapper", userContactApplyMapper);
    }

    private UserContactApply apply(Integer id, String receiveUserId) {
        UserContactApply a = new UserContactApply();
        a.setApplyId(id);
        a.setReceiveUserId(receiveUserId);
        return a;
    }

    @Test
    void findApplyListByPage把query原样透传给mapper并返回() {
        // 首页场景：lastApplyId 传 Long.MAX_VALUE
        UserContactApplyQuery query = new UserContactApplyQuery();
        query.setReceiveUserId("U1001");
        query.setLastApplyId(Long.MAX_VALUE);
        query.setPageSize(20);
        query.setQueryContactInfo(true);

        List<UserContactApply> expect = Arrays.asList(
                apply(30, "U1001"), apply(29, "U1001"), apply(28, "U1001"));
        when(userContactApplyMapper.selectApplyListByPage(query)).thenReturn(expect);

        List<UserContactApply> result = service.findApplyListByPage(query);

        assertNotNull(result);
        assertEquals(3, result.size());
        assertSame(expect, result);
        // 确认透传的就是同一个 query 对象，没有被改字段
        verify(userContactApplyMapper).selectApplyListByPage(query);
    }

    @Test
    void findApplyListByPage翻页时用上一页末尾的applyId做游标() {
        UserContactApplyQuery query = new UserContactApplyQuery();
        query.setReceiveUserId("U1001");
        // 上一页最后一条 applyId 是 28
        query.setLastApplyId(28L);
        query.setPageSize(20);

        List<UserContactApply> expect = Arrays.asList(apply(27, "U1001"), apply(26, "U1001"));
        when(userContactApplyMapper.selectApplyListByPage(query)).thenReturn(expect);

        List<UserContactApply> result = service.findApplyListByPage(query);

        assertEquals(2, result.size());
        // 透传的 query 必须是翻页游标值而不是 Long.MAX_VALUE，否则会重复拉首页
        assertEquals(28L, query.getLastApplyId());
        verify(userContactApplyMapper).selectApplyListByPage(query);
    }

    @Test
    void findApplyListByPage限定receiveUserId防止越权() {
        // 接口层要求：当前用户只能查自己的列表，receiveUserId 不能为空/被绕过
        UserContactApplyQuery query = new UserContactApplyQuery();
        query.setReceiveUserId("U1001");
        query.setLastApplyId(Long.MAX_VALUE);
        query.setPageSize(20);

        when(userContactApplyMapper.selectApplyListByPage(query)).thenReturn(Collections.emptyList());

        List<UserContactApply> result = service.findApplyListByPage(query);

        assertTrue(result.isEmpty());
        // 确认 SQL 过滤条件依赖的 receiveUserId 被透传给了 mapper
        ArgumentCaptor<UserContactApplyQuery> captor = ArgumentCaptor.forClass(UserContactApplyQuery.class);
        verify(userContactApplyMapper).selectApplyListByPage(captor.capture());
        assertEquals("U1001", captor.getValue().getReceiveUserId());
    }

    @Test
    void findApplyListByPage当mapper返回null时不会抛NPE() {
        UserContactApplyQuery query = new UserContactApplyQuery();
        query.setReceiveUserId("U1001");
        query.setLastApplyId(Long.MAX_VALUE);
        query.setPageSize(20);

        when(userContactApplyMapper.selectApplyListByPage(any())).thenReturn(null);

        List<UserContactApply> result = service.findApplyListByPage(query);
        // service 只是透传，mapper 返回 null 就直接返回 null，不额外包装
        assertEquals(null, result);
    }

    @Test
    void query的lastApplyId字段存取正确() {
        UserContactApplyQuery query = new UserContactApplyQuery();
        // 默认值应为 null（由 controller/调用方决定是否置为 Long.MAX_VALUE）
        assertEquals(null, query.getLastApplyId());

        query.setLastApplyId(12345L);
        assertEquals(12345L, query.getLastApplyId());

        // 首页最大游标值边界
        query.setLastApplyId(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, query.getLastApplyId());
    }
}
