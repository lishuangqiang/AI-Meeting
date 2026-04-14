package com.hewei.hzyjy.xunzhi.interview.application.guard;

import com.hewei.hzyjy.xunzhi.interview.config.InterviewAiGuardConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiCallGuardServiceTest {

    private AiCallGuardService service;
    private ExecutorService aiIoExecutor;

    @AfterEach
    void tearDown() {
        if (aiIoExecutor != null) {
            aiIoExecutor.shutdownNow();
        }
    }

    @Test
    void shouldClassifyTimeoutAsAiTimeout() {
        InterviewAiGuardConfiguration configuration = new InterviewAiGuardConfiguration();
        configuration.setEnable(true);
        configuration.setStages(new HashMap<>());
        configuration.getStages().put(InterviewAiGuardStage.INTERVIEW_EVALUATION,
                new InterviewAiGuardConfiguration.StagePolicy(20L, 5, 0, 0L));

        aiIoExecutor = Executors.newFixedThreadPool(2);
        service = new AiCallGuardService(configuration, new SimpleMeterRegistry(), aiIoExecutor);

        InterviewAiGuardException ex = assertThrows(
                InterviewAiGuardException.class,
                () -> service.execute(InterviewAiGuardStage.INTERVIEW_EVALUATION, "req-1", () -> {
                    Thread.sleep(100L);
                    return "ok";
                })
        );

        assertEquals(InterviewAiGuardErrorCode.AI_TIMEOUT, ex.getErrorCode());
    }

    @Test
    void shouldPassThroughWhenGuardDisabled() {
        InterviewAiGuardConfiguration configuration = new InterviewAiGuardConfiguration();
        configuration.setEnable(false);
        aiIoExecutor = Executors.newFixedThreadPool(1);
        service = new AiCallGuardService(configuration, new SimpleMeterRegistry(), aiIoExecutor);

        String result = service.execute(InterviewAiGuardStage.INTERVIEW_EVALUATION, "req-2", () -> "ok");

        assertEquals("ok", result);
    }
}
