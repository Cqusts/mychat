package com.mychat.service.impl;

import com.mychat.entity.po.UserContactApply;
import com.mychat.entity.query.UserContactApplyQuery;
import com.mychat.entity.vo.UserContactApplyCursorVO;
import com.mychat.mappers.UserContactApplyMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 好友申请列表游标分页（按申请时间倒序）改动相关逻辑的单元测试。
 * 只测 loadApplyByCursor 和 add 里 applyTime 兜底这两块新增逻辑，全部用 Mockito 打桩 mapper，不连数据库。
 */
@ExtendWith(MockitoExtension.class)
class UserContactApplyServiceImplTest {

    @Mock
    private UserContactApplyMapper<UserContactApply, UserContactApplyQuery> userContactApplyMapper;

    @InjectMocks
    private UserContactApplyServiceImpl service;

    private UserContactApply buildApply(Integer applyId, long applyTime) {
        UserContactApply a = new UserContactApply();
        a.setApplyId(applyId);
        a.setLastApplyTime(applyTime);
        a.setReceiveUserId("receiver001");
        return a;
    }

    /**
     * 首屏（不传 cursor）时：数据量 > pageSize，应从最新开始截断到 pageSize 条，
     * 并以最后一条的 applyTime_id 生成 nextCursor。
     */
    @Test
    void 首屏数据多于页大小返回最新size条并生成nextCursor() {
        // 造 3 条，pageSize=2
        List<UserContactApply> dbList = new ArrayList<>();
        dbList.add(buildApply(1, 3000L));
        dbList.add(buildApply(2, 2000L));
        dbList.add(buildApply(3, 1000L));
        when(userContactApplyMapper.selectList(any())).thenReturn(dbList);

        UserContactApplyCursorVO vo = service.loadApplyByCursor("receiver001", null, 2);

        assertNotNull(vo.getList());
        assertEquals(2, vo.getList().size());
        // 截断后应保留最新两条
        assertEquals(1, vo.getList().get(0).getApplyId());
        assertEquals(2, vo.getList().get(1).getApplyId());
        // nextCursor 取最后一条（第2条，id=2, time=2000）
        assertEquals("2000_2", vo.getNextCursor());

        // 验证查询条件：每页查 size+1=3 条
        ArgumentCaptor<UserContactApplyQuery> captor = ArgumentCaptor.forClass(UserContactApplyQuery.class);
        verify(userContactApplyMapper).selectList(captor.capture());
        UserContactApplyQuery q = captor.getValue();
        assertEquals("receiver001", q.getReceiveUserId());
        assertEquals(3, q.getPageSize());
        assertNull(q.getCursorLastApplyTime());
    }

    /**
     * 数据量恰好 <= pageSize 时，查不到更多，nextCursor 应为 null。
     */
    @Test
    void 数据量小于等于页大小时nextCursor为空() {
        List<UserContactApply> dbList = new ArrayList<>();
        dbList.add(buildApply(1, 3000L));
        dbList.add(buildApply(2, 2000L));
        when(userContactApplyMapper.selectList(any())).thenReturn(dbList);

        UserContactApplyCursorVO vo = service.loadApplyByCursor("receiver001", null, 2);

        assertNotNull(vo.getList());
        assertEquals(2, vo.getList().size());
        assertNull(vo.getNextCursor(), "返回条数未超过 pageSize 时不应有 nextCursor");
    }

    /**
     * 带合法游标时，应把 applyTime 和 id 解析出来放到查询条件里做复合键过滤。
     */
    @Test
    void 合法游标被解析为查询条件() {
        List<UserContactApply> dbList = new ArrayList<>();
        dbList.add(buildApply(3, 1000L));
        when(userContactApplyMapper.selectList(any())).thenReturn(dbList);

        service.loadApplyByCursor("receiver001", "2000_2", 2);

        ArgumentCaptor<UserContactApplyQuery> captor = ArgumentCaptor.forClass(UserContactApplyQuery.class);
        verify(userContactApplyMapper).selectList(captor.capture());
        UserContactApplyQuery q = captor.getValue();
        assertEquals(2000L, q.getCursorLastApplyTime());
        assertEquals(Integer.valueOf(2), q.getCursorApplyId());
    }

    /**
     * 游标不是 applyTime_id 这种两段格式时，直接返回空列表，不再查库。
     */
    @Test
    void 游标格式非法返回空列表() {
        UserContactApplyCursorVO vo = service.loadApplyByCursor("receiver001", "just_a_bad_cursor", 2);

        assertNotNull(vo.getList());
        assertTrue(vo.getList().isEmpty());
        assertNull(vo.getNextCursor());
        verify(userContactApplyMapper, never()).selectList(any());
    }

    /**
     * 游标虽然分成了两段但数字解析失败（比如 id 段不是整数），也应返回空列表。
     */
    @Test
    void 游标数字段无法解析返回空列表() {
        UserContactApplyCursorVO vo = service.loadApplyByCursor("receiver001", "abc_123", 2);

        assertNotNull(vo.getList());
        assertTrue(vo.getList().isEmpty());
        assertNull(vo.getNextCursor());
        verify(userContactApplyMapper, never()).selectList(any());
    }

    /**
     * pageSize 为 null 时按默认 SIZE15 取数（即查 16 条判断是否还有更多）。
     */
    @Test
    void pageSize为空时使用默认15() {
        List<UserContactApply> dbList = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            dbList.add(buildApply(i, (long) (2000 - i)));
        }
        when(userContactApplyMapper.selectList(any())).thenReturn(dbList);

        UserContactApplyCursorVO vo = service.loadApplyByCursor("receiver001", null, null);

        assertEquals(15, vo.getList().size());
        // 第15条的时间是 2000-15=1985
        assertEquals("1985_15", vo.getNextCursor());

        ArgumentCaptor<UserContactApplyQuery> captor = ArgumentCaptor.forClass(UserContactApplyQuery.class);
        verify(userContactApplyMapper).selectList(captor.capture());
        assertEquals(16, captor.getValue().getPageSize());
    }

    /**
     * add 时 lastApplyTime 传 0 或负数，应兜底为当前系统时间戳再入库。
     */
    @Test
    void add时applyTime为0或负数兜底为当前时间() {
        UserContactApply bean0 = new UserContactApply();
        bean0.setLastApplyTime(0L);
        when(userContactApplyMapper.insert(any())).thenReturn(1);
        service.add(bean0);
        assertNotNull(bean0.getLastApplyTime());
        assertTrue(bean0.getLastApplyTime() > 0, "0 应被兜底为当前时间戳");

        UserContactApply beanNeg = new UserContactApply();
        beanNeg.setLastApplyTime(-100L);
        service.add(beanNeg);
        assertTrue(beanNeg.getLastApplyTime() > 0, "负数应被兜底为当前时间戳");
    }

    /**
     * add 时 lastApplyTime 为 null，不应兜底（保持 null 原样入库）。
     */
    @Test
    void add时applyTime为null不兜底() {
        UserContactApply bean = new UserContactApply();
        bean.setLastApplyTime(null);
        when(userContactApplyMapper.insert(any())).thenReturn(1);
        service.add(bean);
        assertNull(bean.getLastApplyTime());
    }
}
