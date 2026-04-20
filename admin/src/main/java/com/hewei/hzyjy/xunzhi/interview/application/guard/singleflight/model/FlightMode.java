package com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model;

import java.util.Locale;

/**
 * 定义 AI single-flight 的运行模式，
 * 支持仅本地复用、仅分布式复用以及分布式失败后回退本地的混合模式。
 *
 * @author 程序员牛肉
 */
public enum FlightMode {
    LOCAL,
    DISTRIBUTED,
    HYBRID;

    public static FlightMode from(String value) {
        if (value == null || value.isBlank()) {
            return LOCAL;
        }
        try {
            return FlightMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return LOCAL;
        }
    }
}
