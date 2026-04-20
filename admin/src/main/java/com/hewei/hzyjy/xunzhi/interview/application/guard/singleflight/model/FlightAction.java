package com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model;

import java.util.Locale;

/**
 * 定义分布式 single-flight 协调过程中可能出现的动作类型，
 * 例如新 owner 抢占、接管、follower 等待以及结果回放。
 *
 * @author 程序员牛肉
 */
public enum FlightAction {
    OWNER_NEW,
    OWNER_TAKEOVER,
    FOLLOWER_WAIT,
    REPLAY_SUCCESS,
    REPLAY_FAILURE;

    public static FlightAction from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return FlightAction.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
