package com.hewei.hzyjy.xunzhi.interview.application.guard;

import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.xunzhi.interview.config.InterviewAiSingleFlightConfiguration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 分布式 flight 的 Redis 访问层，负责通过 Lua/CAS 维护元数据状态机、
 * 持久化执行结果以及支撑 owner 心跳续租和 follower 查询。
 *
 * @author 程序员牛肉
 */
@Repository
@RequiredArgsConstructor
public class FlightCoordinatorRepository {

    private static final String META_KEY_PREFIX = "ai:flight:meta:";
    private static final String RESULT_KEY_PREFIX = "ai:flight:result:";
    private static final String OWNER_SEQ_KEY = "ai:flight:owner-seq";

    private static final String ACQUIRE_OR_JOIN_SCRIPT_TEXT =
            "local status = redis.call('HGET', KEYS[1], 'status') "
                    + "if not status then "
                    + "  local token = redis.call('INCR', KEYS[2]) "
                    + "  redis.call('HSET', KEYS[1], 'status', 'PENDING', 'stage', ARGV[1], 'ownerId', ARGV[2], 'ownerToken', token, 'requestKey', ARGV[3], 'sessionId', ARGV[4], 'createdAt', ARGV[5], 'updatedAt', ARGV[5], 'heartbeatAt', ARGV[5], 'expireAt', ARGV[6], 'retryable', '0') "
                    + "  redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[7])) "
                    + "  return 'OWNER_NEW|' .. token "
                    + "end "
                    + "if status == 'SUCCEEDED' then return 'REPLAY_SUCCESS|' .. status end "
                    + "if status == 'FAILED' then "
                    + "  local retryable = redis.call('HGET', KEYS[1], 'retryable') or '0' "
                    + "  if retryable == '1' then "
                    + "    local token = redis.call('INCR', KEYS[2]) "
                    + "    redis.call('HSET', KEYS[1], 'status', 'PENDING', 'stage', ARGV[1], 'ownerId', ARGV[2], 'ownerToken', token, 'requestKey', ARGV[3], 'sessionId', ARGV[4], 'updatedAt', ARGV[5], 'heartbeatAt', ARGV[5], 'expireAt', ARGV[6], 'errorType', '', 'errorCode', '', 'retryable', '0') "
                    + "    redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[7])) "
                    + "    return 'OWNER_TAKEOVER|' .. token "
                    + "  end "
                    + "  local errorType = redis.call('HGET', KEYS[1], 'errorType') or '' "
                    + "  local errorCode = redis.call('HGET', KEYS[1], 'errorCode') or '' "
                    + "  return 'REPLAY_FAILURE|0|' .. errorType .. '|' .. errorCode "
                    + "end "
                    + "if status == 'CANCELLED' or status == 'EXPIRED' then return 'REPLAY_FAILURE|1|' .. status .. '|STATE' end "
                    + "local heartbeatAt = tonumber(redis.call('HGET', KEYS[1], 'heartbeatAt') or '0') "
                    + "local takeoverDetectMillis = tonumber(ARGV[8]) "
                    + "if heartbeatAt > 0 and (tonumber(ARGV[5]) - heartbeatAt) <= takeoverDetectMillis then "
                    + "  redis.call('HINCRBY', KEYS[1], 'followerCount', 1) "
                    + "  return 'FOLLOWER_WAIT|' .. (redis.call('HGET', KEYS[1], 'ownerToken') or '') "
                    + "end "
                    + "local token = redis.call('INCR', KEYS[2]) "
                    + "redis.call('HSET', KEYS[1], 'status', 'PENDING', 'stage', ARGV[1], 'ownerId', ARGV[2], 'ownerToken', token, 'requestKey', ARGV[3], 'sessionId', ARGV[4], 'updatedAt', ARGV[5], 'heartbeatAt', ARGV[5], 'expireAt', ARGV[6], 'errorType', '', 'errorCode', '', 'retryable', '0') "
                    + "redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[7])) "
                    + "return 'OWNER_TAKEOVER|' .. token";

    private static final String MARK_RUNNING_SCRIPT_TEXT =
            "if redis.call('HGET', KEYS[1], 'ownerId') ~= ARGV[1] then return 0 end "
                    + "if redis.call('HGET', KEYS[1], 'ownerToken') ~= ARGV[2] then return 0 end "
                    + "local status = redis.call('HGET', KEYS[1], 'status') "
                    + "if status ~= 'PENDING' and status ~= 'RUNNING' then return 0 end "
                    + "redis.call('HSET', KEYS[1], 'status', 'RUNNING', 'updatedAt', ARGV[3], 'heartbeatAt', ARGV[3], 'expireAt', ARGV[4]) "
                    + "redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[5])) "
                    + "return 1";

    private static final String HEARTBEAT_SCRIPT_TEXT =
            "if redis.call('HGET', KEYS[1], 'ownerId') ~= ARGV[1] then return 0 end "
                    + "if redis.call('HGET', KEYS[1], 'ownerToken') ~= ARGV[2] then return 0 end "
                    + "if redis.call('HGET', KEYS[1], 'status') ~= 'RUNNING' then return 0 end "
                    + "redis.call('HSET', KEYS[1], 'updatedAt', ARGV[3], 'heartbeatAt', ARGV[3], 'expireAt', ARGV[4], 'lastHeartbeatNode', ARGV[1]) "
                    + "redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[5])) "
                    + "return 1";

    private static final String STORE_RESULT_SCRIPT_TEXT =
            "if redis.call('HGET', KEYS[1], 'ownerId') ~= ARGV[1] then return 0 end "
                    + "if redis.call('HGET', KEYS[1], 'ownerToken') ~= ARGV[2] then return 0 end "
                    + "local status = redis.call('HGET', KEYS[1], 'status') "
                    + "if status ~= 'PENDING' and status ~= 'RUNNING' then return 0 end "
                    + "redis.call('HSET', KEYS[2], 'payload', ARGV[3], 'codec', ARGV[4], 'compressed', ARGV[5], 'rawSize', ARGV[6], 'storedSize', ARGV[7], 'checksum', ARGV[8], 'contentType', ARGV[9], 'finishedAt', ARGV[10], 'ownerToken', ARGV[2]) "
                    + "redis.call('PEXPIRE', KEYS[2], tonumber(ARGV[11])) "
                    + "return 1";

    private static final String FINISH_SUCCESS_SCRIPT_TEXT =
            "if redis.call('HGET', KEYS[1], 'ownerId') ~= ARGV[1] then return 0 end "
                    + "if redis.call('HGET', KEYS[1], 'ownerToken') ~= ARGV[2] then return 0 end "
                    + "local status = redis.call('HGET', KEYS[1], 'status') "
                    + "if status ~= 'PENDING' and status ~= 'RUNNING' then return 0 end "
                    + "redis.call('HSET', KEYS[1], 'status', 'SUCCEEDED', 'updatedAt', ARGV[3], 'expireAt', ARGV[4], 'resultRef', KEYS[2], 'errorType', '', 'errorCode', '', 'retryable', '0') "
                    + "redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[5])) "
                    + "return 1";

    private static final String FINISH_FAILURE_SCRIPT_TEXT =
            "if redis.call('HGET', KEYS[1], 'ownerId') ~= ARGV[1] then return 0 end "
                    + "if redis.call('HGET', KEYS[1], 'ownerToken') ~= ARGV[2] then return 0 end "
                    + "local status = redis.call('HGET', KEYS[1], 'status') "
                    + "if status ~= 'PENDING' and status ~= 'RUNNING' then return 0 end "
                    + "redis.call('HSET', KEYS[1], 'status', 'FAILED', 'updatedAt', ARGV[3], 'expireAt', ARGV[4], 'errorType', ARGV[5], 'errorCode', ARGV[6], 'retryable', ARGV[7], 'lastEvent', 'owner_failed') "
                    + "redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[8])) "
                    + "return 1";

    private static final DefaultRedisScript<String> ACQUIRE_OR_JOIN_SCRIPT = stringScript(ACQUIRE_OR_JOIN_SCRIPT_TEXT);
    private static final DefaultRedisScript<Long> MARK_RUNNING_SCRIPT = longScript(MARK_RUNNING_SCRIPT_TEXT);
    private static final DefaultRedisScript<Long> HEARTBEAT_SCRIPT = longScript(HEARTBEAT_SCRIPT_TEXT);
    private static final DefaultRedisScript<Long> STORE_RESULT_SCRIPT = longScript(STORE_RESULT_SCRIPT_TEXT);
    private static final DefaultRedisScript<Long> FINISH_SUCCESS_SCRIPT = longScript(FINISH_SUCCESS_SCRIPT_TEXT);
    private static final DefaultRedisScript<Long> FINISH_FAILURE_SCRIPT = longScript(FINISH_FAILURE_SCRIPT_TEXT);

    private final StringRedisTemplate stringRedisTemplate;

    public FlightAcquireResult acquireOrJoin(String stage, String requestKey, String ownerId, String sessionId,
                                             InterviewAiSingleFlightConfiguration.StageFlightPolicy policy) {
        long now = System.currentTimeMillis();
        long runningTtlMillis = policy == null || policy.getRunningTtlMillis() == null || policy.getRunningTtlMillis() <= 0
                ? 15000L
                : policy.getRunningTtlMillis();
        String raw = stringRedisTemplate.execute(
                ACQUIRE_OR_JOIN_SCRIPT,
                List.of(metaKey(requestKey), OWNER_SEQ_KEY),
                StrUtil.blankToDefault(stage, "interview-default"),
                StrUtil.blankToDefault(ownerId, "unknown-node"),
                StrUtil.blankToDefault(requestKey, "no-key"),
                StrUtil.blankToDefault(sessionId, "no-session"),
                String.valueOf(now),
                String.valueOf(now + runningTtlMillis),
                String.valueOf(runningTtlMillis),
                String.valueOf(policy == null ? 10000L : policy.getTakeoverDetectMillis())
        );
        return parseAcquireResult(raw);
    }

    public boolean markRunning(String requestKey, String ownerId, Long ownerToken, long runningTtlMillis) {
        long now = System.currentTimeMillis();
        Long result = stringRedisTemplate.execute(
                MARK_RUNNING_SCRIPT,
                Collections.singletonList(metaKey(requestKey)),
                StrUtil.blankToDefault(ownerId, "unknown-node"),
                String.valueOf(ownerToken),
                String.valueOf(now),
                String.valueOf(now + runningTtlMillis),
                String.valueOf(runningTtlMillis)
        );
        return Long.valueOf(1L).equals(result);
    }

    public boolean heartbeat(String requestKey, String ownerId, Long ownerToken, long runningTtlMillis) {
        long now = System.currentTimeMillis();
        Long result = stringRedisTemplate.execute(
                HEARTBEAT_SCRIPT,
                Collections.singletonList(metaKey(requestKey)),
                StrUtil.blankToDefault(ownerId, "unknown-node"),
                String.valueOf(ownerToken),
                String.valueOf(now),
                String.valueOf(now + runningTtlMillis),
                String.valueOf(runningTtlMillis)
        );
        return Long.valueOf(1L).equals(result);
    }

    public boolean storeResult(String requestKey, String ownerId, Long ownerToken, FlightStoredResult storedResult, long resultTtlMillis) {
        if (storedResult == null) {
            return false;
        }
        Long result = stringRedisTemplate.execute(
                STORE_RESULT_SCRIPT,
                List.of(metaKey(requestKey), resultKey(requestKey)),
                StrUtil.blankToDefault(ownerId, "unknown-node"),
                String.valueOf(ownerToken),
                StrUtil.blankToDefault(storedResult.getPayload(), ""),
                StrUtil.blankToDefault(storedResult.getCodec(), "none"),
                Boolean.TRUE.equals(storedResult.getCompressed()) ? "1" : "0",
                String.valueOf(storedResult.getRawSize() == null ? 0 : storedResult.getRawSize()),
                String.valueOf(storedResult.getStoredSize() == null ? 0 : storedResult.getStoredSize()),
                StrUtil.blankToDefault(storedResult.getChecksum(), ""),
                StrUtil.blankToDefault(storedResult.getContentType(), "text/plain"),
                String.valueOf(storedResult.getFinishedAt() == null ? System.currentTimeMillis() : storedResult.getFinishedAt()),
                String.valueOf(resultTtlMillis)
        );
        return Long.valueOf(1L).equals(result);
    }

    public boolean finishSuccess(String requestKey, String ownerId, Long ownerToken, long resultTtlMillis) {
        long now = System.currentTimeMillis();
        Long result = stringRedisTemplate.execute(
                FINISH_SUCCESS_SCRIPT,
                List.of(metaKey(requestKey), resultKey(requestKey)),
                StrUtil.blankToDefault(ownerId, "unknown-node"),
                String.valueOf(ownerToken),
                String.valueOf(now),
                String.valueOf(now + resultTtlMillis),
                String.valueOf(resultTtlMillis)
        );
        return Long.valueOf(1L).equals(result);
    }

    public boolean finishFailure(String requestKey, String ownerId, Long ownerToken,
                                 FlightErrorType errorType, String errorCode, boolean retryable, long ttlMillis) {
        long now = System.currentTimeMillis();
        Long result = stringRedisTemplate.execute(
                FINISH_FAILURE_SCRIPT,
                Collections.singletonList(metaKey(requestKey)),
                StrUtil.blankToDefault(ownerId, "unknown-node"),
                String.valueOf(ownerToken),
                String.valueOf(now),
                String.valueOf(now + ttlMillis),
                errorType == null ? FlightErrorType.UNEXPECTED.name() : errorType.name(),
                StrUtil.blankToDefault(errorCode, "FLIGHT_FAILED"),
                retryable ? "1" : "0",
                String.valueOf(ttlMillis)
        );
        return Long.valueOf(1L).equals(result);
    }

    public FlightStoredResult getStoredResult(String requestKey) {
        Map<Object, Object> raw = stringRedisTemplate.opsForHash().entries(resultKey(requestKey));
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return FlightStoredResult.builder()
                .payload(asString(raw.get("payload")))
                .codec(asString(raw.get("codec")))
                .compressed("1".equals(asString(raw.get("compressed"))) || "true".equalsIgnoreCase(asString(raw.get("compressed"))))
                .rawSize(asInteger(raw.get("rawSize")))
                .storedSize(asInteger(raw.get("storedSize")))
                .checksum(asString(raw.get("checksum")))
                .contentType(asString(raw.get("contentType")))
                .finishedAt(asLong(raw.get("finishedAt")))
                .ownerToken(asLong(raw.get("ownerToken")))
                .build();
    }

    public FlightMetaSnapshot getMeta(String requestKey) {
        Map<Object, Object> raw = stringRedisTemplate.opsForHash().entries(metaKey(requestKey));
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return FlightMetaSnapshot.builder()
                .stage(asString(raw.get("stage")))
                .status(FlightStatus.from(asString(raw.get("status"))))
                .ownerId(asString(raw.get("ownerId")))
                .ownerToken(asLong(raw.get("ownerToken")))
                .heartbeatAt(asLong(raw.get("heartbeatAt")))
                .retryable("1".equals(asString(raw.get("retryable"))) || "true".equalsIgnoreCase(asString(raw.get("retryable"))))
                .errorType(FlightErrorType.from(asString(raw.get("errorType"))))
                .errorCode(asString(raw.get("errorCode")))
                .build();
    }

    private FlightAcquireResult parseAcquireResult(String raw) {
        if (StrUtil.isBlank(raw)) {
            return FlightAcquireResult.builder().action(FlightAction.FOLLOWER_WAIT).build();
        }
        String[] parts = raw.split("\\|", -1);
        FlightAction action = FlightAction.from(parts[0]);
        FlightAcquireResult.FlightAcquireResultBuilder builder = FlightAcquireResult.builder().action(action);
        if (action == FlightAction.OWNER_NEW || action == FlightAction.OWNER_TAKEOVER || action == FlightAction.FOLLOWER_WAIT) {
            builder.ownerToken(parseLong(parts, 1));
        }
        if (action == FlightAction.REPLAY_SUCCESS && parts.length > 1) {
            builder.status(FlightStatus.from(parts[1]));
        }
        if (action == FlightAction.REPLAY_FAILURE) {
            builder.retryable("1".equals(valueAt(parts, 1)) || "true".equalsIgnoreCase(valueAt(parts, 1)));
            builder.errorType(FlightErrorType.from(valueAt(parts, 2)));
            builder.errorCode(valueAt(parts, 3));
        }
        return builder.build();
    }

    private Long parseLong(String[] values, int index) {
        String value = valueAt(values, index);
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String valueAt(String[] values, int index) {
        return values != null && index >= 0 && index < values.length ? values[index] : null;
    }

    private static DefaultRedisScript<String> stringScript(String scriptText) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptText(scriptText);
        script.setResultType(String.class);
        return script;
    }

    private static DefaultRedisScript<Long> longScript(String scriptText) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(scriptText);
        script.setResultType(Long.class);
        return script;
    }

    private String metaKey(String requestKey) {
        return META_KEY_PREFIX + requestKey;
    }

    private String resultKey(String requestKey) {
        return RESULT_KEY_PREFIX + requestKey;
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private Integer asInteger(Object value) {
        Long longValue = asLong(value);
        return longValue == null ? null : longValue.intValue();
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
