package com.easychat.ai.eval;

import com.easychat.ai.AiAgentRegistry;
import com.easychat.ai.AiWorkflowEngine;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.UserContactStatusEnum;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.query.UserContactQuery;
import com.easychat.mappers.UserContactMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 评测跑批：把一批需求一条一条喂给流水线。
 *
 * 必须串行。并发跑的话几条任务会抢同一个代码工作区
 * （CoderWorkspace 里 checkout -B 是全局的，两条任务同时改会互相覆盖），
 * 而且耗时数据也会被线程池排队时间污染，测出来的中位耗时没有意义。
 */
@Component("aiEvalRunner")
public class AiEvalRunner {

    private static final Logger logger = LoggerFactory.getLogger(AiEvalRunner.class);

    /**
     * 轮询任务是否结束的间隔
     */
    private static final long POLL_INTERVAL_MS = 5000;

    @Value("${ai.eval.task-timeout-minutes:45}")
    private Integer taskTimeoutMinutes;

    @Resource
    private AiWorkflowEngine aiWorkflowEngine;

    @Resource
    private AiAgentRegistry aiAgentRegistry;

    @Resource
    private AiEvalRecorder aiEvalRecorder;

    //评测是独立于业务的工具，宁可在这里重复六行查询，
    //也不为它把 ChatMessageService 的接口撑大
    @Resource
    private UserContactMapper<UserContact, UserContactQuery> userContactMapper;

    /**
     * 同一时间只允许一批在跑，避免手抖点两次把数据搅在一起
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile String progress = "空闲";

    public boolean isRunning() {
        return running.get();
    }

    public String getProgress() {
        return progress;
    }

    /**
     * 跑一批。这个方法自己会阻塞很久，调用方要放到独立线程里跑
     *
     * @param requirements 需求列表
     * @param repeat       每条跑几次。temperature不为0时单次结果有随机性，
     *                     只跑一遍的完成率没有统计意义
     */
    public void run(String groupId, String sessionId, TokenUserInfoDto requester,
                    List<String> requirements, int repeat) {
        if (!running.compareAndSet(false, true)) {
            logger.warn("已经有一批评测在跑了，忽略本次请求");
            return;
        }
        try {
            List<String> agentIds = findGroupAgentIds(groupId);
            int total = requirements.size() * repeat;
            int index = 0;
            long batchStart = System.currentTimeMillis();
            logger.info("[EVAL] 跑批开始：{}条需求 x {}次 = {}个任务", requirements.size(), repeat, total);

            for (int round = 1; round <= repeat; round++) {
                for (String requirement : requirements) {
                    index++;
                    progress = "第" + index + "/" + total + "条：" + requirement;
                    logger.info("[EVAL] ===== {}/{} 第{}轮 需求：{}", index, total, round, requirement);

                    String taskId = aiWorkflowEngine.startTask(
                            groupId, sessionId, requester, requirement, agentIds);
                    if (taskId == null) {
                        logger.warn("[EVAL] 任务没能启动，跳过：{}", requirement);
                        continue;
                    }
                    if (!awaitFinish(taskId)) {
                        logger.warn("[EVAL] 任务超时未结束，继续下一条：taskId={}", taskId);
                    }
                }
            }
            long minutes = (System.currentTimeMillis() - batchStart) / 60000;
            progress = "已完成，共" + total + "个任务，耗时" + minutes + "分钟";
            logger.info("[EVAL] 跑批结束，耗时{}分钟。调 /eval/report 看指标", minutes);
        } catch (Exception e) {
            progress = "异常中断：" + e.getMessage();
            logger.error("[EVAL] 跑批异常", e);
        } finally {
            running.set(false);
        }
    }

    /**
     * 等一个任务结束
     *
     * @return 正常结束返回true，超时返回false
     */
    private boolean awaitFinish(String taskId) {
        long deadline = System.currentTimeMillis() + taskTimeoutMinutes * 60_000L;
        while (System.currentTimeMillis() < deadline) {
            if (!aiWorkflowEngine.isTaskRunning(taskId)) {
                return true;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 群里有哪些助手。必须传真实成员而不是全部已配置助手，
     * 否则缺席检查形同虚设，任务会跑到中间才因为少一个角色而失败
     */
    private List<String> findGroupAgentIds(String groupId) {
        UserContactQuery query = new UserContactQuery();
        query.setContactId(groupId);
        query.setStatus(UserContactStatusEnum.FRIEND.getStatus());
        List<UserContact> members = userContactMapper.selectList(query);
        List<String> agentIds = new ArrayList<>();
        if (members == null) {
            return agentIds;
        }
        for (UserContact member : members) {
            if (aiAgentRegistry.getById(member.getUserId()) != null) {
                agentIds.add(member.getUserId());
            }
        }
        return agentIds;
    }

    public AiEvalRecorder getRecorder() {
        return aiEvalRecorder;
    }
}
