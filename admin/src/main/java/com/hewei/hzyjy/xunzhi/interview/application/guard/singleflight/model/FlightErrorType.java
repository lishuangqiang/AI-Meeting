package com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model;

import java.util.Locale;

/**
 * 定义分布式 single-flight 执行失败的错误分类，
 * 用于统一标识超时、过载、供应商异常和业务校验失败等场景。
 *
 * @author 程序员牛肉
 */
public enum FlightErrorType {
    TIMEOUT,
    OVERLOAD,
    PROVIDER,
    VALIDATION,
    UNEXPECTED,
    CANCELLED,
    EXPIRED;

    public static FlightErrorType from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return FlightErrorType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
