package com.easychat.controller;

import com.easychat.ai.eval.AiEvalRecorder;
import com.easychat.ai.eval.AiEvalRunner;
import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.vo.ResponseVO;
import com.easychat.exception.BusinessException;
import com.easychat.service.UserContactService;
import com.easychat.utils.StringTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotEmpty;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 流水线评测接口。
 *
 * 这几个接口能凭一次请求驱动模型改代码、跑maven，所以默认整个关掉
 * （ai.eval.enabled=false），只在自己本地测的时候开。
 * 别在公网环境打开它
 */
@RestController
@RequestMapping("/eval")
public class AiEvalController extends ABaseController {

    private static final Logger logger = LoggerFactory.getLogger(AiEvalController.class);

    /**
     * 一次最多提交多少条需求，防手滑
     */
    private static final int MAX_REQUIREMENTS = 30;

    private static final int MAX_REPEAT = 5;

    @Resource
    private AiEvalRunner aiEvalRunner;

    @Resource
    private AiEvalRecorder aiEvalRecorder;

    @Resource
    private UserContactService userContactService;

    /**
     * 跑一批。立刻返回，跑批在后台线程里串行执行，进度看 /eval/status
     *
     * @param requirements 需求列表，一行一条
     * @param repeat       每条跑几次，默认2
     */
    @RequestMapping("/batch")
    @GlobalInterceptor
    public ResponseVO batch(HttpServletRequest request,
                            @NotEmpty String groupId,
                            @NotEmpty String sessionId,
                            @NotEmpty String requirements,
                            Integer repeat) {
        checkEnabled();
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        checkGroupMember(tokenUserInfoDto.getUserId(), groupId);

        List<String> list = new ArrayList<>();
        for (String line : requirements.split("\n")) {
            String trimmed = line.trim();
            //允许在需求文件里写 # 注释和空行
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                list.add(trimmed);
            }
        }
        if (list.isEmpty()) {
            throw new BusinessException("需求列表是空的");
        }
        if (list.size() > MAX_REQUIREMENTS) {
            throw new BusinessException("一次最多" + MAX_REQUIREMENTS + "条需求");
        }
        int times = repeat == null ? 2 : Math.max(1, Math.min(repeat, MAX_REPEAT));
        if (aiEvalRunner.isRunning()) {
            throw new BusinessException("已经有一批在跑了：" + aiEvalRunner.getProgress());
        }

        //跑批要阻塞几十分钟，绝不能占着HTTP线程
        Thread worker = new Thread(
                () -> aiEvalRunner.run(groupId, sessionId, tokenUserInfoDto, list, times),
                "ai-eval-runner");
        worker.setDaemon(true);
        worker.start();

        Map<String, Object> result = new HashMap<>();
        result.put("requirementCount", list.size());
        result.put("repeat", times);
        result.put("taskCount", list.size() * times);
        logger.info("[EVAL] 已提交跑批：{}条 x {}次", list.size(), times);
        return getSuccessResponseVO(result);
    }

    @RequestMapping("/status")
    @GlobalInterceptor
    public ResponseVO status(HttpServletRequest request) {
        checkEnabled();
        Map<String, Object> result = new HashMap<>();
        result.put("running", aiEvalRunner.isRunning());
        result.put("progress", aiEvalRunner.getProgress());
        result.put("recorded", aiEvalRecorder.loadAll().size());
        return getSuccessResponseVO(result);
    }

    /**
     * 指标报告。跑完直接看这个
     */
    @RequestMapping("/report")
    @GlobalInterceptor
    public ResponseVO report(HttpServletRequest request) {
        checkEnabled();
        return getSuccessResponseVO(aiEvalRecorder.report());
    }

    /**
     * 报告的纯文本版，直接贴进简历或者笔记
     */
    @RequestMapping("/reportText")
    @GlobalInterceptor
    public ResponseVO reportText(HttpServletRequest request, Double totalCostYuan) {
        checkEnabled();
        return getSuccessResponseVO(
                AiEvalTextReport.render(aiEvalRecorder.report(), totalCostYuan));
    }

    /**
     * 清空历史记录。换了任务集或者改了配置要重新测时先调它，
     * 不然新旧两批数据混在一起算出来的指标没有意义
     */
    @RequestMapping("/clear")
    @GlobalInterceptor
    public ResponseVO clear(HttpServletRequest request) {
        checkEnabled();
        if (aiEvalRunner.isRunning()) {
            throw new BusinessException("正在跑批，不能清空");
        }
        aiEvalRecorder.clear();
        return getSuccessResponseVO(null);
    }

    private void checkEnabled() {
        if (!aiEvalRecorder.isEnabled()) {
            throw new BusinessException("评测功能未开启，请先设置 ai.eval.enabled=true 并重启");
        }
    }

    private void checkGroupMember(String userId, String groupId) {
        if (StringTools.isEmpty(groupId)) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        UserContact contact = userContactService.getUserContactByUserIdAndContactId(userId, groupId);
        if (contact == null) {
            throw new BusinessException("你不在这个群里");
        }
    }
}
