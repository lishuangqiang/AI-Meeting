package com.hewei.hzyjy.xunzhi.media.infrastructure.integration;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XunfeiAudioServiceAssemblerTest {

    @Test
    void noPgsPartial_ShouldReplaceCurrentLiveSnapshot() throws Exception {
        Object assembler = newAssembler();

        apply(assembler, 1, null, null, 0, 1000, "A", false);
        assertEquals("A", buildSnapshot(assembler));

        apply(assembler, 2, null, null, 1001, 2000, "AB", false);
        assertEquals("AB", buildSnapshot(assembler));
        assertEquals("", buildCommittedText(assembler, 2, false));
        assertEquals("AB", buildLiveText(assembler, "", "AB", "AB", false));

        apply(assembler, 3, null, null, 2001, 3000, "ABC", false);
        assertEquals("ABC", buildSnapshot(assembler));
        assertEquals("", buildCommittedText(assembler, 3, false));
        assertEquals("ABC", buildLiveText(assembler, "", "ABC", "ABC", false));
    }

    @Test
    void rplPacket_ShouldRemoveReplacedSentenceRange() throws Exception {
        Object assembler = newAssembler();

        apply(assembler, 1, "apd", null, null, null, "first ", false);
        apply(assembler, 2, "apd", null, null, null, "wrong ", false);
        apply(assembler, 3, "apd", null, null, null, "tail", false);
        apply(assembler, 2, "rpl", new int[]{2, 2}, null, null, "correct ", false);

        assertEquals("first correct tail", buildSnapshot(assembler));
    }

    @Test
    void finalPacket_ShouldCommitSnapshotAndClearLiveText() throws Exception {
        Object assembler = newAssembler();

        apply(assembler, 1, null, null, 0, 1000, "final answer", true);
        String snapshot = buildSnapshot(assembler);
        String committedText = buildCommittedText(assembler, 1, true);

        assertEquals("final answer", snapshot);
        assertEquals("final answer", committedText);
        assertEquals("", buildLiveText(assembler, committedText, snapshot, "final answer", true));
    }

    private Object newAssembler() throws Exception {
        XunfeiAudioService service = new XunfeiAudioService(null, null);
        Class<?> assemblerClass = Class.forName(
                "com.hewei.hzyjy.xunzhi.media.infrastructure.integration.XunfeiAudioService$AstTranscriptionAssembler"
        );
        Constructor<?> constructor = assemblerClass.getDeclaredConstructor(XunfeiAudioService.class);
        constructor.setAccessible(true);
        return constructor.newInstance(service);
    }

    private void apply(Object assembler,
                       int segmentId,
                       String pgs,
                       int[] rg,
                       Integer bg,
                       Integer ed,
                       String text,
                       boolean finalized) throws Exception {
        Method method = assembler.getClass().getDeclaredMethod(
                "apply",
                int.class,
                String.class,
                int[].class,
                Integer.class,
                Integer.class,
                String.class,
                boolean.class
        );
        method.setAccessible(true);
        method.invoke(assembler, segmentId, pgs, rg, bg, ed, text, finalized);
    }

    private String buildSnapshot(Object assembler) throws Exception {
        Method method = assembler.getClass().getDeclaredMethod("buildSnapshot");
        method.setAccessible(true);
        return (String) method.invoke(assembler);
    }

    private String buildCommittedText(Object assembler, int activeSegmentId, boolean finalPacket) throws Exception {
        Method method = assembler.getClass().getDeclaredMethod(
                "buildCommittedText",
                int.class,
                boolean.class
        );
        method.setAccessible(true);
        return (String) method.invoke(assembler, activeSegmentId, finalPacket);
    }

    private String buildLiveText(Object assembler,
                                 String committedText,
                                 String displayText,
                                 String segmentText,
                                 boolean finalPacket) throws Exception {
        Method method = assembler.getClass().getDeclaredMethod(
                "buildLiveText",
                String.class,
                String.class,
                String.class,
                boolean.class
        );
        method.setAccessible(true);
        return (String) method.invoke(assembler, committedText, displayText, segmentText, finalPacket);
    }
}
