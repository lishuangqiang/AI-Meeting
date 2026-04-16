package com.hewei.hzyjy.xunzhi.common.enums;

import com.hewei.hzyjy.xunzhi.common.convention.errorcode.IErrorCode;

/**
 * Interview domain error codes.
 */
public enum InterviewErrorCodeEnum implements IErrorCode {

    SESSION_ID_EMPTY("B000400", "sessionId cannot be empty"),
    INVALID_USER_ID("B000401", "invalid userId"),
    CONVERSATION_NOT_FOUND("B000402", "conversation does not exist"),
    CONVERSATION_ACCESS_DENIED("B000403", "no permission to access this conversation"),
    INTERVIEW_SESSION_NOT_FOUND("B000412", "interview session does not exist"),
    INTERVIEW_SESSION_ACCESS_DENIED("B000413", "no permission to access this interview session"),
    INTERVIEW_SESSION_INVALID_STATE("B000414", "interview session state is invalid"),
    AI_TIMEOUT("B000415", "AI call timed out, please retry"),
    AI_OVERLOADED("B000416", "AI service is busy, please retry"),
    AI_UNAVAILABLE("B000417", "AI service is unavailable, please retry"),

    DEMEANOR_FILE_UPLOAD_FAILED("B000404", "demeanor image upload failed"),
    DEMEANOR_USER_PHOTO_NOT_FOUND("B000405", "user photo not found"),
    AGENT_CONFIG_NOT_FOUND("B000406", "agent configuration does not exist"),
    DEMEANOR_AI_RESPONSE_INVALID("B000407", "AI response invalid"),
    DEMEANOR_AI_RESPONSE_CONTENT_MISSING("B000408", "AI response content missing"),
    DEMEANOR_AI_RESPONSE_FORMAT_ERROR("B000409", "AI response format error"),
    DEMEANOR_AI_RESPONSE_PARSE_ERROR("B000410", "AI response parse error"),
    DEMEANOR_EVALUATION_FAILED("B000411", "demeanor evaluation failed");

    private final String code;
    private final String message;

    InterviewErrorCodeEnum(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
