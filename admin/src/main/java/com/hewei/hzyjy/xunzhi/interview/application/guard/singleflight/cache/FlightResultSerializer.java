package com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.cache;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model.FlightStoredResult;
import com.hewei.hzyjy.xunzhi.interview.config.InterviewAiSingleFlightConfiguration;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * AI 结果序列化组件，负责对结果进行压缩、编码、校验摘要生成与反序列化恢复，
 * 以便安全地写入 Redis 并控制存储成本。
 *
 * @author 程序员牛肉
 */
@Component
public class FlightResultSerializer {

    public FlightStoredResult serialize(String value, Long ownerToken,
                                        InterviewAiSingleFlightConfiguration.StageFlightPolicy policy) {
        String safeValue = value == null ? "" : value;
        byte[] rawBytes = safeValue.getBytes(StandardCharsets.UTF_8);
        int threshold = policy == null || policy.getCompressionThresholdBytes() == null
                ? Integer.MAX_VALUE
                : Math.max(0, policy.getCompressionThresholdBytes());
        String codec = normalizeCodec(policy == null ? null : policy.getCompressionCodec());
        boolean shouldCompress = rawBytes.length >= threshold && !"none".equals(codec);
        byte[] storedBytes = shouldCompress ? gzip(rawBytes) : rawBytes;
        return FlightStoredResult.builder()
                .payload(Base64.getEncoder().encodeToString(storedBytes))
                .codec(shouldCompress ? codec : "none")
                .compressed(shouldCompress)
                .rawSize(rawBytes.length)
                .storedSize(storedBytes.length)
                .checksum(DigestUtil.sha256Hex(rawBytes))
                .contentType("text/plain")
                .finishedAt(System.currentTimeMillis())
                .ownerToken(ownerToken)
                .build();
    }

    public String deserialize(FlightStoredResult storedResult) {
        if (storedResult == null || StrUtil.isBlank(storedResult.getPayload())) {
            return null;
        }
        byte[] storedBytes = Base64.getDecoder().decode(storedResult.getPayload());
        byte[] rawBytes = Boolean.TRUE.equals(storedResult.getCompressed())
                ? gunzip(storedBytes)
                : storedBytes;
        String checksum = DigestUtil.sha256Hex(rawBytes);
        if (StrUtil.isNotBlank(storedResult.getChecksum()) && !storedResult.getChecksum().equals(checksum)) {
            throw new IllegalStateException("flight result checksum mismatch");
        }
        return new String(rawBytes, StandardCharsets.UTF_8);
    }

    private String normalizeCodec(String codec) {
        if (codec == null || codec.isBlank()) {
            return "gzip";
        }
        String normalized = codec.trim().toLowerCase();
        if ("gzip".equals(normalized)) {
            return normalized;
        }
        return "none";
    }

    private byte[] gzip(byte[] rawBytes) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream)) {
                gzipOutputStream.write(rawBytes);
            }
            return outputStream.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to gzip flight result", ex);
        }
    }

    private byte[] gunzip(byte[] storedBytes) {
        try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(storedBytes))) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            gzipInputStream.transferTo(outputStream);
            return outputStream.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to gunzip flight result", ex);
        }
    }
}
