package com.mychat.entity.dto;

import java.io.Serializable;

/**
 * 方案里的一项改动：改哪个文件、做什么、怎么做
 */
public class PlanChangeDto implements Serializable {

    private static final long serialVersionUID = 7712398471293847L;

    private String path;

    private String action;

    private String detail;

    /**
     * 这个路径在代码索引里存不存在。
     * 不存在不一定是错的（可能要新建），但值得提醒编码阶段确认一下
     */
    private Boolean exists;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getAction() {
        return action == null ? "" : action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getDetail() {
        return detail == null ? "" : detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Boolean getExists() {
        return exists;
    }

    public void setExists(Boolean exists) {
        this.exists = exists;
    }
}
