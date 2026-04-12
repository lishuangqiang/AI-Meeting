package com.hewei.hzyjy.xunzhi.interview.service.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class RedisInterviewCacheStore implements InterviewCacheStore {

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public String getValue(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    @Override
    public void setValue(String key, String value, long ttlHours) {
        stringRedisTemplate.opsForValue().set(key, value);
        expire(key, ttlHours);
    }

    @Override
    public Long increment(String key, long delta) {
        return stringRedisTemplate.opsForValue().increment(key, delta);
    }

    @Override
    public void expire(String key, long ttlHours) {
        stringRedisTemplate.expire(key, ttlHours, TimeUnit.HOURS);
    }

    @Override
    public void deleteKeys(Collection<String> keys) {
        stringRedisTemplate.delete(keys);
    }
}

