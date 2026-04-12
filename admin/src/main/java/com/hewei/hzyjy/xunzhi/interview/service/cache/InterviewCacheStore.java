package com.hewei.hzyjy.xunzhi.interview.service.cache;

import java.util.Collection;

public interface InterviewCacheStore {

    String getValue(String key);

    void setValue(String key, String value, long ttlHours);

    Long increment(String key, long delta);

    void expire(String key, long ttlHours);

    void deleteKeys(Collection<String> keys);
}

