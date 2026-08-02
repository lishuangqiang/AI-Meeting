package com.hewei.hzyjy.xunzhi.interview.service.model;

import lombok.Getter;

/**
 * 答题原子提交结果（对应 Lua 返回码）。
 */
@Getter
public class AnswerCommitResult {

    public enum Code {
        /**
         * 1：本次提交成功。
         */
        COMMITTED,
        /**
         * 2：requestId 已提交且指纹一致，返回回放信息。
         */
        REPLAYED,
        /**
         * 0：flow 版本冲突（并发推进），调用方按 STALE 处理。
         */
        VERSION_CONFLICT,
        /**
         * -1：requestId 已提交但指纹不符。
         */
        FINGERPRINT_MISMATCH,
        /**
         * -2：入参非法或键类型/内容预检失败。
         */
        INVALID_ARGUMENT,
        /**
         * -3：提交记录 JSON 损坏，fail closed。
         */
        CORRUPT_RECORD
    }

    private final Code code;
    private final Long flowVersion;
    private final String currentQuestionNumber;
    private final String status;
    private final Integer totalScore;

    private AnswerCommitResult(Code code, Long flowVersion, String currentQuestionNumber, String status, Integer totalScore) {
        this.code = code;
        this.flowVersion = flowVersion;
        this.currentQuestionNumber = currentQuestionNumber;
        this.status = status;
        this.totalScore = totalScore;
    }

    public static AnswerCommitResult committed(Long flowVersion, String currentQuestionNumber, String status, Integer totalScore) {
        return new AnswerCommitResult(Code.COMMITTED, flowVersion, currentQuestionNumber, status, totalScore);
    }

    public static AnswerCommitResult replayed(Long flowVersion, String currentQuestionNumber, String status, Integer totalScore) {
        return new AnswerCommitResult(Code.REPLAYED, flowVersion, currentQuestionNumber, status, totalScore);
    }

    public static AnswerCommitResult versionConflict() {
        return new AnswerCommitResult(Code.VERSION_CONFLICT, null, null, null, null);
    }

    public static AnswerCommitResult fingerprintMismatch() {
        return new AnswerCommitResult(Code.FINGERPRINT_MISMATCH, null, null, null, null);
    }

    public static AnswerCommitResult invalid() {
        return new AnswerCommitResult(Code.INVALID_ARGUMENT, null, null, null, null);
    }

    public static AnswerCommitResult corruptRecord() {
        return new AnswerCommitResult(Code.CORRUPT_RECORD, null, null, null, null);
    }
}
