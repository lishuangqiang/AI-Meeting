package com.hewei.hzyjy.xunzhi.interview.application.guard;

import lombok.Builder;
import lombok.Value;

/**
 * 分布式协调器中持久化后的 AI 结果对象，
 * 记录真实负载、压缩信息、大小、校验和以及归属 owner 等数据。
 *
 * @author 程序员牛肉
 */
@Value
@Builder
public class FlightStoredResult {
    String payload;
    String codec;
    Boolean compressed;
    Integer rawSize;
    Integer storedSize;
    String checksum;
    String contentType;
    Long finishedAt;
    Long ownerToken;
}
