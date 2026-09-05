package com.mychat.entity.vo;

import com.mychat.entity.po.UserContactApply;

import java.util.List;

/**
 * 好友申请列表游标分页返回结果
 */
public class UserContactApplyCursorVO {
    private List<UserContactApply> list;

    private String nextCursor;

    public UserContactApplyCursorVO() {
    }

    public UserContactApplyCursorVO(List<UserContactApply> list, String nextCursor) {
        this.list = list;
        this.nextCursor = nextCursor;
    }

    public List<UserContactApply> getList() {
        return list;
    }

    public void setList(List<UserContactApply> list) {
        this.list = list;
    }

    public String getNextCursor() {
        return nextCursor;
    }

    public void setNextCursor(String nextCursor) {
        this.nextCursor = nextCursor;
    }
}
