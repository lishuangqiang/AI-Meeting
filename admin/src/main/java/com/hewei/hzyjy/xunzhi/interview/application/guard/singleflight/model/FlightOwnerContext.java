package com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model;

import com.hewei.hzyjy.xunzhi.interview.config.InterviewAiSingleFlightConfiguration;
import lombok.Builder;
import lombok.Value;

/**
 * owner 节点执行 AI 请求时的上下文对象，
 * 汇总当前阶段、请求键、owner 身份以及对应的 stage 策略。
 *
 * @author 程序员牛肉
 */
@Value
@Builder
public class FlightOwnerContext {
    String stage;
    String requestKey;
    String ownerId;
    Long ownerToken;
    InterviewAiSingleFlightConfiguration.StageFlightPolicy policy;
}
