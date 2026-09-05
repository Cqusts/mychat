package com.mychat.entity.po;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 针对「群聊增加'仅群主可发言'开关」改动新增的 GroupInfo.ownerOnlySpeak 字段做纯逻辑测试。
 *
 * 本次改动只动了两个文件：GroupInfo 实体加了 ownerOnlySpeak 字段，
 * GroupInfoMapper.xml 加了 owner_only_speak 的列映射与 insert 支持。
 * 这里测的是实体字段的读写与序列化行为（不依赖 DB/Redis/Spring 容器）。
 */
class GroupInfoOwnerOnlySpeakTest {

    @Test
    void 新建实体时开关字段默认未赋值() {
        GroupInfo groupInfo = new GroupInfo();
        //DB 列默认值是 0，但实体自身不预设默认值，未设置时应为 null
        assertNull(groupInfo.getOwnerOnlySpeak(), "新建实体未设置 ownerOnlySpeak 时应为 null（由 DB 列默认值兜底）");
    }

    @Test
    void 开启开关后能正确读取() {
        GroupInfo groupInfo = new GroupInfo();
        groupInfo.setOwnerOnlySpeak(1);
        assertEquals(1, groupInfo.getOwnerOnlySpeak());
    }

    @Test
    void 关闭开关后能正确读取() {
        GroupInfo groupInfo = new GroupInfo();
        groupInfo.setOwnerOnlySpeak(0);
        assertEquals(0, groupInfo.getOwnerOnlySpeak());
    }

    @Test
    void 开关可被重置回空() {
        GroupInfo groupInfo = new GroupInfo();
        groupInfo.setOwnerOnlySpeak(1);
        groupInfo.setOwnerOnlySpeak(null);
        assertNull(groupInfo.getOwnerOnlySpeak(), "显式置 null 后应回到未赋值状态");
    }

    @Test
    void 开关字段参与Json序列化() {
        GroupInfo groupInfo = new GroupInfo();
        groupInfo.setGroupId("group-001");
        groupInfo.setGroupName("测试群");
        groupInfo.setGroupOwnerId("user-owner");
        groupInfo.setOwnerOnlySpeak(1);

        String json = JSON.toJSONString(groupInfo);
        //ownerOnlySpeak 必须能传到前端/接口，否则开关状态无法被读取
        assertTrue(json.contains("\"ownerOnlySpeak\":1"), "序列化应包含 ownerOnlySpeak=1，实际:" + json);
    }

    @Test
    void 开关关闭时序列化为0() {
        GroupInfo groupInfo = new GroupInfo();
        groupInfo.setGroupId("group-002");
        groupInfo.setOwnerOnlySpeak(0);

        String json = JSON.toJSONString(groupInfo);
        assertTrue(json.contains("\"ownerOnlySpeak\":0"), "序列化应包含 ownerOnlySpeak=0，实际:" + json);
    }

    @Test
    void 开关未设置时不参与序列化() {
        GroupInfo groupInfo = new GroupInfo();
        groupInfo.setGroupId("group-003");

        String json = JSON.toJSONString(groupInfo);
        assertTrue(!json.contains("ownerOnlySpeak"), "未设置的 null 字段不应出现在 JSON 里，实际:" + json);
    }
}
