package com.hewei.hzyjy.xunzhi.media.infrastructure.integration;

import cn.xfyun.config.SparkIatModelEnum;
import com.hewei.hzyjy.xunzhi.common.config.storage.ApplicationStorageProperties;
import com.hewei.hzyjy.xunzhi.common.convention.exception.ClientException;
import com.hewei.hzyjy.xunzhi.toolkit.xunfei.SparkIatUtil;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Audio transcription service for synchronous and asynchronous speech-to-text flows.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudioTranscriptionService {

    private final SparkIatUtil sparkIatUtil;
    private final ApplicationStorageProperties storageProperties;
    @Resource(name = "cpuComputeExecutor")
    private ExecutorService cpuComputeExecutor;

    public CompletableFuture<String> transcribeAsync(MultipartFile audioFile,
                                                     Consumer<String> partialResultCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return transcribeSync(audioFile, partialResultCallback);
            } catch (Exception ex) {
                log.error("Async audio transcription failed", ex);
                throw new RuntimeException("Audio transcription failed: " + ex.getMessage(), ex);
            }
        }, cpuComputeExecutor);
    }

    public String transcribeAudio(MultipartFile audioFile) throws Exception {
        return transcribeSync(audioFile, null);
    }

    public String transcribeSync(MultipartFile audioFile,
                                 Consumer<String> partialResultCallback) throws Exception {
        validateAudioFile(audioFile);
        Path tempFile = createTempAudioFile(audioFile);

        try {
            SparkIatUtil.IatConfig config = buildTranscriptionConfig();
            String result = sparkIatUtil.transcribeSync(tempFile.toFile(), config, partialResultCallback);
            log.info("Audio transcription completed, file={}, resultLength={}",
                    audioFile.getOriginalFilename(), result.length());
            return result;
        } finally {
            cleanupTempFile(tempFile);
        }
    }

    public void transcribeWithCallback(MultipartFile audioFile,
                                       AudioTranscriptionCallback callback) {
        try {
            validateAudioFile(audioFile);
            Path tempFile = createTempAudioFile(audioFile);
            SparkIatUtil.IatConfig config = buildTranscriptionConfig();

            sparkIatUtil.transcribeAsync(tempFile.toFile(), config, new SparkIatUtil.IatCallback() {
                @Override
                public void onSuccess(String result) {
                    cleanupTempFile(tempFile);
                    callback.onSuccess(result);
                }

                @Override
                public void onError(Exception error) {
                    cleanupTempFile(tempFile);
                    callback.onError(error);
                }

                @Override
                public void onPartialResult(String partialResult) {
                    callback.onPartialResult(partialResult);
                }
            });
        } catch (Exception ex) {
            log.error("Failed to start async audio transcription", ex);
            callback.onError(ex);
        }
    }

    private void validateAudioFile(MultipartFile audioFile) {
        if (audioFile == null || audioFile.isEmpty()) {
            throw new ClientException("audio file must not be empty");
        }

        long maxSize = 50L * 1024 * 1024;
        if (audioFile.getSize() > maxSize) {
            throw new ClientException("audio file size must not exceed 50MB");
        }

        String filename = audioFile.getOriginalFilename();
        if (filename == null) {
            return;
        }

        String extension = getFileExtension(filename).toLowerCase();
        if (isSupportedAudioFormat(extension)) {
            return;
        }

        if (".m4a".equals(extension) || ".aac".equals(extension)) {
            throw new ClientException("unsupported audio format: " + extension
                    + ". Supported formats are pcm, wav, mp3 and flac. Please convert the file and retry.");
        }
        throw new ClientException("unsupported audio format: " + extension
                + ". Supported formats are pcm, wav, mp3 and flac.");
    }

    private boolean isSupportedAudioFormat(String extension) {
        return ".pcm".equals(extension)
                || ".wav".equals(extension)
                || ".mp3".equals(extension)
                || ".flac".equals(extension);
    }

    private String getFileExtension(String filename) {
        int lastDotIndex = filename.lastIndexOf('.');
        return lastDotIndex > 0 ? filename.substring(lastDotIndex) : "";
    }

    private Path createTempAudioFile(MultipartFile audioFile) throws IOException {
        Path tempDir = storageProperties.getAudioTempPath();
        Files.createDirectories(tempDir);

        String extension = getFileExtension(audioFile.getOriginalFilename());
        String fileName = "audio_transcription_" + System.currentTimeMillis()
                + "_" + Thread.currentThread().getId() + extension;
        Path tempFile = tempDir.resolve(fileName);

        try (var inputStream = audioFile.getInputStream()) {
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }

        log.debug("Created audio temp file={}, size={} bytes", tempFile, audioFile.getSize());
        return tempFile;
    }

    private SparkIatUtil.IatConfig buildTranscriptionConfig() {
        return sparkIatUtil.createDefaultConfig();
    }

    @SuppressWarnings("unused")
    private SparkIatUtil.IatConfig buildCustomTranscriptionConfig(SparkIatModelEnum model,
                                                                  String dwa,
                                                                  int timeoutSeconds) {
        return sparkIatUtil.createCustomConfig(model, dwa, timeoutSeconds);
    }

    private void cleanupTempFile(Path tempFile) {
        if (tempFile == null || !Files.exists(tempFile)) {
            return;
        }

        try {
            Files.delete(tempFile);
            log.debug("Deleted temp file={}", tempFile);
        } catch (Exception ex) {
            log.warn("Failed to delete temp file={}, reason={}", tempFile, ex.getMessage());
            tempFile.toFile().deleteOnExit();
        }
    }

    public interface AudioTranscriptionCallback {

        void onSuccess(String result);

        void onError(Exception error);

        default void onPartialResult(String partialResult) {
        }
    }
}
