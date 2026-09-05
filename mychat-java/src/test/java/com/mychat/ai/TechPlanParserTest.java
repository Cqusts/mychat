package com.mychat.ai;

import com.mychat.entity.dto.PlanChangeDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案结构化解析。
 *
 * 实测 11 条失败 100% 在编码阶段的"定位该改哪个文件"上，
 * 根因是架构师从没看过代码、方案是凭想象写的，程序员拿到一段文字还得从零重新找。
 * 把方案里的改动清单抽成结构化数据交给编码阶段，就是为了消掉这个环节
 */
class TechPlanParserTest {

    private static final String PLAN = """
            会话列表要支持按昵称模糊搜索，在现有查询上加一个可选条件即可，
            不需要新建表，注意昵称索引缺失可能带来的全表扫描风险。

            【改动清单】
            - mychat-java/src/main/java/com/mychat/entity/query/ChatSessionUserQuery.java | 新增字段 | nickNameFuzzy，用于模糊匹配
            - mychat-java/src/main/resources/com/mychat/mappers/ChatSessionUserMapper.xml | 修改SQL | selectList 里加 nickName like 条件
            - mychat-java/src/main/java/com/mychat/controller/ChatController.java | 新增接口 | searchSession 接收 keyword

            【验收标准】
            - 传入昵称片段能返回匹配的会话
            - 传入不存在的昵称返回空列表
            - 不传 keyword 时行为和原来一致
            """;

    @Test
    void 解析出改动清单的三个字段() {
        List<PlanChangeDto> changes = TechPlanParser.parseChanges(PLAN, null);

        assertEquals(3, changes.size());
        assertEquals("mychat-java/src/main/java/com/mychat/entity/query/ChatSessionUserQuery.java",
                changes.get(0).getPath());
        assertEquals("新增字段", changes.get(0).getAction());
        assertTrue(changes.get(0).getDetail().contains("nickNameFuzzy"));
    }

    @Test
    void 解析出验收标准() {
        List<String> acceptance = TechPlanParser.parseAcceptance(PLAN);

        assertEquals(3, acceptance.size());
        assertTrue(acceptance.get(0).contains("昵称片段"));
    }

    @Test
    void 正文里的横线不会被当成清单项() {
        //【改动清单】小节之外的内容一律不解析，否则正文里随便一个破折号都会混进来
        String noisy = "方案说明\n- 这是正文里的一条，不该被当成改动\n\n" + PLAN;

        assertEquals(3, TechPlanParser.parseChanges(noisy, null).size());
    }

    @Test
    void 没写清单时降级成空列表不报错() {
        String plain = "就是加个字段，没什么好说的。";

        assertTrue(TechPlanParser.parseChanges(plain, null).isEmpty());
        assertTrue(TechPlanParser.parseAcceptance(plain).isEmpty());
        assertTrue(TechPlanParser.parseChanges(null, null).isEmpty());
    }

    @Test
    void 只写了路径没写动作也能解析() {
        String terse = "【改动清单】\n- a/B.java\n";

        List<PlanChangeDto> changes = TechPlanParser.parseChanges(terse, null);

        assertEquals(1, changes.size());
        assertEquals("a/B.java", changes.get(0).getPath());
        assertEquals("", changes.get(0).getAction());
    }

    @Test
    void 反斜杠路径统一成正斜杠() {
        String windows = "【改动清单】\n- a\\b\\C.java | 改 | 说明\n";

        assertEquals("a/b/C.java", TechPlanParser.parseChanges(windows, null).get(0).getPath());
    }

    @Test
    void 渲染给编码阶段时标出索引里没有的文件() {
        List<PlanChangeDto> changes = TechPlanParser.parseChanges(PLAN, null);
        //index 传 null 时 exists 全是 false，模拟"索引里查不到"
        String rendered = TechPlanParser.renderForCoder(changes);

        assertTrue(rendered.contains("不用再自己找文件"), rendered);
        assertTrue(rendered.contains("可能需要新建"), "查不到的文件要提醒确认");
        assertTrue(rendered.contains("ChatSessionUserQuery.java"));
    }

    @Test
    void 空清单渲染成空串不干扰提示词() {
        assertEquals("", TechPlanParser.renderForCoder(List.of()));
        assertEquals("", TechPlanParser.renderForCoder(null));
    }

    @Test
    void 验收标准要能被测试阶段用() {
        //验收标准是TDD那一步的输入，必须是可判定的行为描述而不是空话
        List<String> acceptance = TechPlanParser.parseAcceptance(PLAN);

        assertFalse(acceptance.isEmpty());
        for (String item : acceptance) {
            assertFalse(item.isBlank());
        }
    }
}
