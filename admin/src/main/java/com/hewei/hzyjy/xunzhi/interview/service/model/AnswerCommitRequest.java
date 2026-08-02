package com.hewei.hzyjy.xunzhi.interview.service.model;

import lombok.Builder;
import lombok.Data;

/**
 * 答题原子提交请求参数。
 * <p>
 * 由 Lua 脚本（ANSWER_COMMIT_SCRIPT）一次原子完成：flow 版本 CAS 推进、
 * 主问题分数聚合（sum/count/avg）、轮次日志追加、幂等提交记录写入与全键 TTL 刷新。
 */
@Data
@Builder
public class AnswerCommitRequest {

    private String sessionId;

    /**
     * 幂等键：调用方保证同一请求内容复用同一 requestId。
     */
    private String requestId;

    /**
     * 指纹：请求对应的题号。
     */
    private String questionNumber;

    /**
     * 指纹：答案内容摘要（sha256），用于拦截 requestId 复用串内容。
     */
    private String answerDigest;

    /**
     * 主问题计分入账；追问（shouldScore=false）只推进 flow，不更新分数聚合键。
     */
    private boolean shouldScore;

    /**
     * 本次得分（0-100），脚本内强制整数与范围校验。
     */
    private Integer score;

    /**
     * 期望 flow 版本（CAS 门禁）。
     */
    private Integer expectedVersion;

    /**
     * 提交后的 flow status：ASKING / FOLLOW_UP / COMPLETED。
     */
    private String status;

    /**
     * 提交后的 flow currentIndex。
     */
    private Integer currentIndex;

    /**
     * 提交后的 flow currentQuestionNumber（完成后为 null）。
     */
    private String currentQuestionNumber;

    private Integer totalQuestions;

    /**
     * 提交后的 flow followUpCount。
     */
    private Integer followUpCount;

    private Integer maxFollowUp;

    private String feedback;

    private String nextQuestionNumber;

    private String nextQuestion;

    private boolean finished;

    /**
     * 当前回答是否为追问。
     */
    private boolean isFollowUp;

    /**
     * 追问分支展示用当前总分（主问题路径由脚本内计算的 avg 覆盖）。
     */
    private Integer currentTotalScore;

    /**
     * InterviewTurnLog 的 JSON 序列化（totalScore 由脚本按最终 avg 修正）。
     */
    private String turnPayload;
}
