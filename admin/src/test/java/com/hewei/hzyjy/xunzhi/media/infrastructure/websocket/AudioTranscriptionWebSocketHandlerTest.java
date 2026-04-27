package com.hewei.hzyjy.xunzhi.media.infrastructure.websocket;

import com.alibaba.fastjson2.JSONObject;
import com.hewei.hzyjy.xunzhi.media.infrastructure.integration.XunfeiAudioService.RealtimeTranscriptionUpdate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioTranscriptionWebSocketHandlerTest {

    @Test
    void createResponse_ShouldExposeSegmentMetadataWhileKeepingFullSnapshot() throws Exception {
        AudioTranscriptionWebSocketHandler handler = new AudioTranscriptionWebSocketHandler();
        RealtimeTranscriptionUpdate update = new RealtimeTranscriptionUpdate(
                "hello world today",
                "hello world ",
                "today",
                "hello world today",
                3,
                "partial",
                12,
                "hello world",
                "rpl",
                new int[]{10, 12},
                320,
                640,
                false
        );

        Method method = AudioTranscriptionWebSocketHandler.class.getDeclaredMethod(
                "createResponse",
                String.class,
                String.class,
                RealtimeTranscriptionUpdate.class,
                boolean.class
        );
        method.setAccessible(true);

        String payload = (String) method.invoke(handler, "transcription", "Partial snapshot", update, true);
        JSONObject json = JSONObject.parseObject(payload);

        assertEquals("transcription", json.getString("type"));
        assertEquals("Partial snapshot", json.getString("message"));
        assertEquals("hello world today", json.getString("data"));
        assertEquals("hello world today", json.getString("fullText"));
        assertEquals("hello world today", json.getString("displayText"));
        assertEquals("hello world ", json.getString("committedText"));
        assertEquals("today", json.getString("liveText"));
        assertEquals(3, json.getIntValue("revision"));
        assertEquals("partial", json.getString("resultStatus"));
        assertTrue(json.getBooleanValue("isSnapshot"));
        assertEquals("replace", json.getString("updateAction"));
        assertEquals(12, json.getIntValue("segmentId"));
        assertEquals(12, json.getIntValue("sentenceSeq"));
        assertEquals("hello world", json.getString("segmentText"));
        assertEquals("rpl", json.getString("pgs"));
        assertNotNull(json.getJSONArray("rg"));
        assertEquals(10, json.getJSONArray("rg").getIntValue(0));
        assertEquals(12, json.getJSONArray("rg").getIntValue(1));
        assertEquals(320, json.getIntValue("bg"));
        assertEquals(640, json.getIntValue("ed"));
        assertFalse(json.getBooleanValue("isFinalPacket"));
    }

    @Test
    void buildFinalUpdate_ShouldKeepLastSegmentMetadataAndMarkFinalPacket() throws Exception {
        AudioTranscriptionWebSocketHandler handler = new AudioTranscriptionWebSocketHandler();
        RealtimeTranscriptionUpdate previous = new RealtimeTranscriptionUpdate(
                "full partial",
                "full ",
                "partial",
                "full partial",
                5,
                "partial",
                7,
                "segment seven",
                "apd",
                new int[]{7, 7},
                1280,
                1640,
                false
        );

        Method method = AudioTranscriptionWebSocketHandler.class.getDeclaredMethod(
                "buildFinalUpdate",
                String.class,
                RealtimeTranscriptionUpdate.class
        );
        method.setAccessible(true);

        RealtimeTranscriptionUpdate finalUpdate =
                (RealtimeTranscriptionUpdate) method.invoke(handler, "final full text", previous);

        assertEquals("final full text", finalUpdate.fullText());
        assertEquals("final full text", finalUpdate.displayText());
        assertEquals("final full text", finalUpdate.committedText());
        assertEquals("", finalUpdate.liveText());
        assertEquals(6, finalUpdate.revision());
        assertEquals("final", finalUpdate.resultStatus());
        assertEquals(7, finalUpdate.segmentId());
        assertEquals("segment seven", finalUpdate.segmentText());
        assertEquals("apd", finalUpdate.pgs());
        assertNotNull(finalUpdate.rg());
        assertEquals(7, finalUpdate.rg()[0]);
        assertEquals(7, finalUpdate.rg()[1]);
        assertEquals(1280, finalUpdate.bg());
        assertEquals(1640, finalUpdate.ed());
        assertTrue(finalUpdate.finalPacket());
    }
}
