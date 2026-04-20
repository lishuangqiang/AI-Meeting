package com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model;

import lombok.Builder;
import lombok.Value;

/**
 * 封装节点对同一 AI 请求执行抢占后的结果，
 * 用于区分当前节点是 owner、follower 还是直接回放历史结果。
 *
 * @author 程序员牛肉
 */
@Value
@Builder
public class FlightAcquireResult {
    FlightAction action;
    Long ownerToken;
    FlightStatus status;
    Boolean retryable;
    FlightErrorType errorType;
    String errorCode;
}
