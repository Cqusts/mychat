package com.mychat.controller;

import com.mychat.annotation.GlobalInterceptor;
import com.mychat.entity.po.UserInfoBeauty;
import com.mychat.entity.query.UserInfoBeautyQuery;
import com.mychat.entity.vo.ResponseVO;
import com.mychat.service.UserInfoBeautyService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotNull;

/**
 * 靓号 Controller
 */
@RestController("userInfoBeautyController")
@RequestMapping("/admin")
@Validated
public class AdminUserInfoBeautyController extends ABaseController {

    @Resource
    private UserInfoBeautyService userInfoBeautyService;

    /**
     * 根据条件分页查询
     */
    @RequestMapping("/loadBeautyAccountList")
    @GlobalInterceptor(checkAdmin = true)
    public ResponseVO loadBeautyAccountList(UserInfoBeautyQuery query) {
        return getSuccessResponseVO(userInfoBeautyService.findListByPage(query));
    }

    @RequestMapping("/saveBeautAccount")
    @GlobalInterceptor(checkAdmin = true)
    public ResponseVO saveBeautAccount(UserInfoBeauty beauty) {
        userInfoBeautyService.saveAccount(beauty);
        return getSuccessResponseVO(null);
    }

    @RequestMapping("/delBeautAccount")
    @GlobalInterceptor(checkAdmin = true)
    public ResponseVO delBeautAccount(@NotNull Integer id) {
        return getSuccessResponseVO(userInfoBeautyService.deleteUserInfoBeautyById(id));
    }
}