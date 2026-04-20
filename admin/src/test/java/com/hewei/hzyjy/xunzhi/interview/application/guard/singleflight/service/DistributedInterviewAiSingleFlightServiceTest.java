package com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.service;

import com.hewei.hzyjy.xunzhi.interview.application.guard.core.InterviewAiGuardStage;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.cache.FlightReplayLocalCache;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.cache.FlightResultSerializer;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.coordinator.FlightCoordinatorRepository;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.coordinator.FlightHeartbeatManager;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.coordinator.FlightNotificationService;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model.FlightAcquireResult;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model.FlightAction;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model.FlightMetaSnapshot;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model.FlightStatus;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model.FlightStoredResult;
import com.hewei.hzyjy.xunzhi.interview.config.InterviewAiSingleFlightConfiguration;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DistributedInterviewAiSingleFlightServiceTest {

    @Test
    void shouldFallbackToLocalWhenDistributedDisabled() {
        InterviewAiSingleFlightConfiguration configuration = newConfig();
        configuration.setDistributedEnabled(false);
        InterviewAiSingleFlightService localService = mock(InterviewAiSingleFlightService.class);
        when(localService.execute(anyString(), any())).thenReturn("local-result");

        DistributedInterviewAiSingleFlightService service = new DistributedInterviewAiSingleFlightService(
                configuration,
                localService,
                mock(FlightCoordinatorRepository.class),
                mock(FlightNotificationService.class),
                mock(FlightHeartbeatManager.class),
                mock(FlightResultSerializer.class),
                mock(FlightReplayLocalCache.class)
        );

        String result = service.execute(InterviewAiGuardStage.INTERVIEW_EVALUATION, "k1", () -> "remote");

        assertEquals("local-result", result);
    }

    @Test
    void shouldExecuteOwnerPathAndStoreResult() {
        InterviewAiSingleFlightConfiguration configuration = newConfig();
        InterviewAiSingleFlightService localService = mock(InterviewAiSingleFlightService.class);
        FlightCoordinatorRepository repository = mock(FlightCoordinatorRepository.class);
        FlightNotificationService notificationService = mock(FlightNotificationService.class);
        FlightHeartbeatManager heartbeatManager = mock(FlightHeartbeatManager.class);
        FlightResultSerializer serializer = mock(FlightResultSerializer.class);
        FlightReplayLocalCache replayLocalCache = mock(FlightReplayLocalCache.class);

        when(repository.acquireOrJoin(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(FlightAcquireResult.builder().action(FlightAction.OWNER_NEW).ownerToken(1L).build());
        when(repository.markRunning(anyString(), anyString(), anyLong(), anyLong())).thenReturn(true);
        when(heartbeatManager.start(any(), any())).thenReturn("hb-1");
        FlightStoredResult storedResult = FlightStoredResult.builder()
                .payload("payload")
                .codec("none")
                .compressed(false)
                .rawSize(5)
                .storedSize(5)
                .checksum("c")
                .contentType("text/plain")
                .finishedAt(System.currentTimeMillis())
                .ownerToken(1L)
                .build();
        when(serializer.serialize(anyString(), anyLong(), any())).thenReturn(storedResult);
        when(repository.storeResult(anyString(), anyString(), anyLong(), any(), anyLong())).thenReturn(true);
        when(repository.finishSuccess(anyString(), anyString(), anyLong(), anyLong())).thenReturn(true);

        DistributedInterviewAiSingleFlightService service = new DistributedInterviewAiSingleFlightService(
                configuration,
                localService,
                repository,
                notificationService,
                heartbeatManager,
                serializer,
                replayLocalCache
        );
        AtomicInteger callCount = new AtomicInteger();

        String result = service.execute(InterviewAiGuardStage.INTERVIEW_EVALUATION, "interview-evaluation|s1|1|a1", () -> {
            callCount.incrementAndGet();
            return "value-1";
        });

        assertEquals("value-1", result);
        assertEquals(1, callCount.get());
        verify(repository).storeResult(anyString(), anyString(), anyLong(), any(), anyLong());
        verify(repository).finishSuccess(anyString(), anyString(), anyLong(), anyLong());
    }

    @Test
    void shouldReturnReplayWithoutExecutingSupplier() {
        InterviewAiSingleFlightConfiguration configuration = newConfig();
        InterviewAiSingleFlightService localService = mock(InterviewAiSingleFlightService.class);
        FlightCoordinatorRepository repository = mock(FlightCoordinatorRepository.class);
        FlightNotificationService notificationService = mock(FlightNotificationService.class);
        FlightHeartbeatManager heartbeatManager = mock(FlightHeartbeatManager.class);
        FlightResultSerializer serializer = mock(FlightResultSerializer.class);
        FlightReplayLocalCache replayLocalCache = mock(FlightReplayLocalCache.class);

        when(repository.acquireOrJoin(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(FlightAcquireResult.builder().action(FlightAction.REPLAY_SUCCESS).status(FlightStatus.SUCCEEDED).build());
        when(repository.getMeta(anyString()))
                .thenReturn(FlightMetaSnapshot.builder().status(FlightStatus.SUCCEEDED).build());
        when(repository.getStoredResult(anyString()))
                .thenReturn(FlightStoredResult.builder().payload("payload").codec("none").compressed(false).checksum("x").build());
        when(serializer.deserialize(any())).thenReturn("replay-value");

        DistributedInterviewAiSingleFlightService service = new DistributedInterviewAiSingleFlightService(
                configuration,
                localService,
                repository,
                notificationService,
                heartbeatManager,
                serializer,
                replayLocalCache
        );

        String result = service.execute(InterviewAiGuardStage.INTERVIEW_EVALUATION, "interview-evaluation|s1|1|a1", () -> "new-value");

        assertEquals("replay-value", result);
        verify(serializer).deserialize(any());
        verify(localService, never()).execute(anyString(), any());
    }

    private InterviewAiSingleFlightConfiguration newConfig() {
        InterviewAiSingleFlightConfiguration configuration = new InterviewAiSingleFlightConfiguration();
        configuration.setEnable(true);
        configuration.setMode("distributed");
        configuration.setDistributedEnabled(true);
        configuration.setFollowerMaxWaitMillis(1000L);
        configuration.setStreamBlockTimeoutMillis(50L);
        configuration.setPollFallbackIntervalMillis(50L);
        configuration.setStagePolicies(new LinkedHashMap<>());
        configuration.getStagePolicies().put(InterviewAiGuardStage.INTERVIEW_EVALUATION,
                new InterviewAiSingleFlightConfiguration.StageFlightPolicy());
        return configuration;
    }
}
