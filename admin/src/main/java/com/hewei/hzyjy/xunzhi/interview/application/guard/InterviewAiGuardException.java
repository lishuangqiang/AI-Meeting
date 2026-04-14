package com.hewei.hzyjy.xunzhi.interview.application.guard;

import lombok.Getter;

/**
 * Exception raised by AI guard layer.
 */
@Getter
public class InterviewAiGuardException extends RuntimeException {

    private final InterviewAiGuardErrorCode errorCode;
    private final String stage;

    public InterviewAiGuardException(
            InterviewAiGuardErrorCode errorCode,
            String stage,
            String message,
            Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.stage = stage;
    }
}
