package com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.cache;

import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model.FlightStoredResult;
import com.hewei.hzyjy.xunzhi.interview.config.InterviewAiSingleFlightConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlightResultSerializerTest {

    private final FlightResultSerializer serializer = new FlightResultSerializer();

    @Test
    void shouldRoundTripCompressedResult() {
        InterviewAiSingleFlightConfiguration.StageFlightPolicy policy = new InterviewAiSingleFlightConfiguration.StageFlightPolicy();
        policy.setCompressionThresholdBytes(1);
        policy.setCompressionCodec("gzip");

        String value = "hello distributed single flight";
        FlightStoredResult storedResult = serializer.serialize(value, 1L, policy);

        assertTrue(Boolean.TRUE.equals(storedResult.getCompressed()));
        assertEquals(value, serializer.deserialize(storedResult));
    }

    @Test
    void shouldKeepSmallResultUncompressed() {
        InterviewAiSingleFlightConfiguration.StageFlightPolicy policy = new InterviewAiSingleFlightConfiguration.StageFlightPolicy();
        policy.setCompressionThresholdBytes(1024);
        policy.setCompressionCodec("gzip");

        FlightStoredResult storedResult = serializer.serialize("small", 2L, policy);

        assertFalse(Boolean.TRUE.equals(storedResult.getCompressed()));
        assertEquals("small", serializer.deserialize(storedResult));
    }
}
