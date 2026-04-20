package com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model;

import java.util.Locale;

/**
 * 定义分布式 AI 请求协调状态机的状态集合，
 * 用于表示请求从运行中到成功、失败、取消或过期的全生命周期。
 *
 * @author 程序员牛肉
 */
public enum FlightStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED;

    public static FlightStatus from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return FlightStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == EXPIRED;
    }
}
