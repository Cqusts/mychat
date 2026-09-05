package com.mychat.controller;

import com.mychat.annotation.GlobalInterceptor;
import com.mychat.entity.config.AppConfig;
import com.mychat.entity.constants.Constants;
import com.mychat.entity.dto.MessageSendDto;
import com.mychat.entity.dto.TokenUserInfoDto;
import com.mychat.entity.enums.MessageTypeEnum;
import com.mychat.entity.enums.ResponseCodeEnum;
import com.mychat.ai.AiWorkflowEngine;
import com.mychat.entity.po.ChatMessage;
import com.mychat.entity.po.UserContact;
import com.mychat.entity.vo.ResponseVO;
import com.mychat.exception.BusinessException;
import com.mychat.service.ChatMessageService;
import com.mychat.service.ChatSessionUserService;
import com.mychat.service.UserContactService;
import com.mychat.utils.StringTools;
import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

@RestController
@RequestMapping("/chat")
public class ChatController extends ABaseController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Resource
    private ChatMessageService chatMessageService;

    @Resource
    private ChatSessionUserService chatSessionUserService;

    @Resource
    private AppConfig appConfig;

    @Resource
    private UserContactService userContactService;

    @Resource
    private AiWorkflowEngine aiWorkflowEngine;

    /**
     * 停掉群里正在跑的AI需求流水线。
     *
     * 模型陷在工具循环里是真的会停不下来的，之前只能重启服务端，
     * 所以这个入口是必需的而不是锦上添花
     */
    @RequestMapping("/stopAiTask")
    @GlobalInterceptor
    public ResponseVO stopAiTask(HttpServletRequest request, @NotEmpty String contactId) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        checkGroupMember(tokenUserInfoDto.getUserId(), contactId);
        int stopped = aiWorkflowEngine.stopGroupTasks(contactId, tokenUserInfoDto.getUserId());
        logger.info("用户请求停止AI任务, userId:{}, groupId:{}, 停掉{}个",
                tokenUserInfoDto.getUserId(), contactId, stopped);
        return getSuccessResponseVO(stopped);
    }

    /**
     * 群里有没有在跑的流水线。切换会话时查一次，避免刷新后按钮消失
     */
    @RequestMapping("/queryAiTaskRunning")
    @GlobalInterceptor
    public ResponseVO queryAiTaskRunning(HttpServletRequest request, @NotEmpty String contactId) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        checkGroupMember(tokenUserInfoDto.getUserId(), contactId);
        return getSuccessResponseVO(aiWorkflowEngine.hasRunningTask(contactId));
    }

    /**
     * 只有群成员能停群里的任务。不校验的话，知道群号就能把别人的任务掐了
     */
    private void checkGroupMember(String userId, String contactId) {
        UserContact contact = userContactService.getUserContactByUserIdAndContactId(userId, contactId);
        if (contact == null) {
            //别用CODE_600("请求参数错误")：参数本身是好的，是这个人不在群里。
            //笼统的错误码会让调用方以为自己传错了，实际排查方向完全跑偏
            throw new BusinessException("你不在这个群里");
        }
    }


    @RequestMapping("/sendMessage")
    @GlobalInterceptor
    public ResponseVO sendMessage(HttpServletRequest request,
                                  @NotEmpty String contactId,
                                  @NotEmpty String messageContent,
                                  @NotNull Integer messageType,
                                  Long fileSize,
                                  String fileName,
                                  Integer fileType) {
        MessageTypeEnum messageTypeEnum = MessageTypeEnum.getByType(messageType);
        if (null == messageTypeEnum || !ArrayUtils.contains(new Integer[]{MessageTypeEnum.CHAT.getType(), MessageTypeEnum.MEDIA_CHAT.getType()}, messageType)) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setContactId(contactId);
        chatMessage.setMessageContent(messageContent);
        chatMessage.setFileSize(fileSize);
        chatMessage.setFileName(fileName);
        chatMessage.setFileType(fileType);
        chatMessage.setMessageType(messageType);
        MessageSendDto messageSendDto = chatMessageService.saveMessage(chatMessage, tokenUserInfoDto);
        return getSuccessResponseVO(messageSendDto);
    }

    @RequestMapping("uploadFile")
    @GlobalInterceptor
    public ResponseVO uploadFile(HttpServletRequest request,
                                 @NotNull Long messageId,
                                 @NotNull MultipartFile file,
                                 @NotNull MultipartFile cover) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        chatMessageService.saveMessageFile(userInfoDto.getUserId(), messageId, file, cover);
        return getSuccessResponseVO(null);
    }

    @RequestMapping("downloadFile")
    @GlobalInterceptor
    public void downloadFile(HttpServletRequest request, HttpServletResponse response,
                             @NotEmpty String fileId,
                             @NotNull Boolean showCover) throws Exception {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        OutputStream out = null;
        FileInputStream in = null;
        try {
            File file = null;
            if (!StringTools.isNumber(fileId)) {
                String avatarFolderName = Constants.FILE_FOLDER_FILE + Constants.FILE_FOLDER_AVATAR_NAME;
                String avatarPath = appConfig.getProjectFolder() + avatarFolderName + fileId + Constants.IMAGE_SUFFIX;
                if (showCover) {
                    avatarPath = avatarPath + Constants.COVER_IMAGE_SUFFIX;
                }
                file = new File(avatarPath);
                if (!file.exists()) {
                    throw new BusinessException(ResponseCodeEnum.CODE_602);
                }
            } else {
                file = chatMessageService.downloadFile(userInfoDto, Long.parseLong(fileId), showCover);
            }
            response.setContentType("application/x-msdownload; charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment;");
            response.setContentLengthLong(file.length());
            in = new FileInputStream(file);
            byte[] byteData = new byte[1024];
            out = response.getOutputStream();
            int len = 0;
            while ((len = in.read(byteData)) != -1) {
                out.write(byteData, 0, len);
            }
            out.flush();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    logger.error("IO异常", e);
                }
            }
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    logger.error("IO异常", e);
                }
            }
        }
    }
}
