package com.easychat.controller;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.config.AppConfig;
import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.po.UserMoments;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.entity.vo.ResponseVO;
import com.easychat.exception.BusinessException;
import com.easychat.service.UserMomentsService;
import com.easychat.utils.DateUtil;
import com.easychat.entity.enums.DateTimePatternEnum;
import com.easychat.utils.StringTools;
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
import jakarta.validation.constraints.Size;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 朋友圈 Controller
 */
@RestController
@RequestMapping("/moments")
public class UserMomentsController extends ABaseController {

    private static final Logger logger = LoggerFactory.getLogger(UserMomentsController.class);

    @Resource
    private UserMomentsService userMomentsService;

    @Resource
    private AppConfig appConfig;

    /**
     * 发布朋友圈动态
     */
    @RequestMapping("/publish")
    @GlobalInterceptor
    public ResponseVO publish(HttpServletRequest request,
                              @Size(max = 1000) String content,
                              Integer type,
                              MultipartFile[] imageFiles) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        // 处理图片上传
        String images = null;
        if (imageFiles != null && imageFiles.length > 0) {
            List<String> imagePathList = new ArrayList<>();
            String month = DateUtil.format(new Date(), DateTimePatternEnum.YYYYMM.getPattern());
            String folderPath = appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + "moments/" + month;
            File folder = new File(folderPath);
            if (!folder.exists()) {
                folder.mkdirs();
            }
            for (MultipartFile imageFile : imageFiles) {
                if (imageFile == null || imageFile.isEmpty()) {
                    continue;
                }
                String fileName = StringTools.getRandomString(15) + StringTools.getFileSuffix(imageFile.getOriginalFilename());
                File destFile = new File(folder.getPath() + "/" + fileName);
                try {
                    imageFile.transferTo(destFile);
                    imagePathList.add("moments/" + month + "/" + fileName);
                } catch (Exception e) {
                    logger.error("朋友圈图片上传失败", e);
                    throw new BusinessException("图片上传失败");
                }
            }
            if (!imagePathList.isEmpty()) {
                images = String.join(",", imagePathList);
                if (type == null) {
                    type = 1; // 图文类型
                }
            }
        }
        UserMoments moments = userMomentsService.publishMoments(tokenUserInfoDto, content, images, type);
        return getSuccessResponseVO(moments);
    }

    /**
     * 获取朋友圈动态流
     */
    @RequestMapping("/loadFeed")
    @GlobalInterceptor
    public ResponseVO loadFeed(HttpServletRequest request, Integer pageNo) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        PaginationResultVO<UserMoments> result = userMomentsService.loadMomentsFeed(tokenUserInfoDto, pageNo);
        return getSuccessResponseVO(result);
    }

    /**
     * 获取指定用户的动态列表
     */
    @RequestMapping("/loadUserMoments")
    @GlobalInterceptor
    public ResponseVO loadUserMoments(HttpServletRequest request, @NotEmpty String userId, Integer pageNo) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        PaginationResultVO<UserMoments> result = userMomentsService.loadUserMoments(userId, tokenUserInfoDto.getUserId(), pageNo);
        return getSuccessResponseVO(result);
    }

    /**
     * 获取动态详情
     */
    @RequestMapping("/getDetail")
    @GlobalInterceptor
    public ResponseVO getDetail(HttpServletRequest request, @NotNull Long momentsId) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        UserMoments moments = userMomentsService.getMomentsDetail(momentsId, tokenUserInfoDto.getUserId());
        return getSuccessResponseVO(moments);
    }

    /**
     * 删除朋友圈动态
     */
    @RequestMapping("/delete")
    @GlobalInterceptor
    public ResponseVO delete(HttpServletRequest request, @NotNull Long momentsId) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        userMomentsService.deleteMoments(tokenUserInfoDto.getUserId(), momentsId);
        return getSuccessResponseVO(null);
    }

    /**
     * 点赞/取消点赞
     */
    @RequestMapping("/like")
    @GlobalInterceptor
    public ResponseVO like(HttpServletRequest request, @NotNull Long momentsId) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        userMomentsService.likeMoments(tokenUserInfoDto, momentsId);
        return getSuccessResponseVO(null);
    }

    /**
     * 发表评论
     */
    @RequestMapping("/comment")
    @GlobalInterceptor
    public ResponseVO comment(HttpServletRequest request,
                              @NotNull Long momentsId,
                              @NotEmpty @Size(max = 500) String content,
                              String replyUserId) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        userMomentsService.commentMoments(tokenUserInfoDto, momentsId, content, replyUserId);
        return getSuccessResponseVO(null);
    }

    /**
     * 下载朋友圈图片
     */
    @RequestMapping("/downloadImage")
    @GlobalInterceptor
    public void downloadImage(HttpServletRequest request, HttpServletResponse response,
                              @NotEmpty String imageId) throws Exception {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        OutputStream out = null;
        FileInputStream in = null;
        try {
            String imagePath = appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + imageId;
            File file = new File(imagePath);
            if (!file.exists()) {
                throw new BusinessException(ResponseCodeEnum.CODE_602);
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
