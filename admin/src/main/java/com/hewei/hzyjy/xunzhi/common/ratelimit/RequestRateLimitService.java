package com.hewei.hzyjy.xunzhi.common.ratelimit;

public interface RequestRateLimitService {

    boolean tryAcquire(String key, RequestRateLimitPolicy policy);
}
