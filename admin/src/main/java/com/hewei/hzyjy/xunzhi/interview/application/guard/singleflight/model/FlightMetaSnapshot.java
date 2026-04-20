package com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model;

import lombok.Builder;
import lombok.Value;

/**
 * flight 元数据快照对象，表示当前请求在协调器中的状态、owner 身份、
 * 最近心跳时间以及失败标记等运行信息。
 *
 * @author 程序员牛肉
 */
@Value
@Builder
public class FlightMetaSnapshot {
    String stage;
    FlightStatus status;
    String ownerId;
    Long ownerToken;
    Long heartbeatAt;
    Boolean retryable;
    FlightErrorType errorType;
    String errorCode;
}
