package com.hewei.hzyjy.xunzhi.toolkit.xunfei;

import cn.xfyun.config.SparkIatModelEnum;
import com.hewei.hzyjy.xunzhi.common.config.xunfei.XunfeiLatProperties;
import jakarta.annotation.Resource;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * 讯飞星火语音转写工具类
 * 支持方言大模型语音听写功能
 */
@Slf4j
@Component
public class SparkIatUtil {

    /**
     * 语音转写回调接口
     */
    public interface IatCallback {
        /**
         * 转写成功回调
         * @param result 转写结果
         */
        void onSuccess(String result);
        
        /**
         * 转写失败回调
         * @param error 错误异常
         */
        void onError(Exception error);
        
        /**
         * 中间结果回调
         * @param partialResult 中间结果
         */
        void onPartialResult(String partialResult);
    }

    /**
     * 语音转写配置类
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IatConfig {

        private String appId;
        private String apiKey;
        private String apiSecret;
        @Builder.Default
        private SparkIatModelEnum model = SparkIatModelEnum.ZH_CN_MULACC;
        @Builder.Default
        private String dwa = "wpgs"; // 流式实时返回
        @Builder.Default
        private int timeoutSeconds = 60; // 超时时间

        public IatConfig(String appId, String apiKey, String apiSecret) {
            this.appId = appId;
            this.apiKey = apiKey;
            this.apiSecret = apiSecret;
            this.model = SparkIatModelEnum.ZH_CN_MULACC;
            this.dwa = "wpgs";
            this.timeoutSeconds = 60;
        }
        
        /**
         * 从xunfeiLatProperties创建默认配置
         * @param xunfeiLatProperties 讯飞配置属性
         * @return IatConfig实例
         */
        public static IatConfig fromProperties(XunfeiLatProperties xunfeiLatProperties) {
            return IatConfig.builder()
                    .appId(xunfeiLatProperties.getAppId())
                    .apiKey(xunfeiLatProperties.getApiKey())
                    .apiSecret(xunfeiLatProperties.getApiSecret())
                    .model(SparkIatModelEnum.ZH_CN_MULACC)
                    .dwa("wpgs")
                    .timeoutSeconds(60)
                    .build();
        }
        
        /**
         * 从xunfeiLatProperties创建自定义配置
         * @param xunfeiLatProperties 讯飞配置属性
         * @param model 语音模型
         * @param dwa 动态修正参数
         * @param timeoutSeconds 超时时间
         * @return IatConfig实例
         */
        public static IatConfig fromProperties(XunfeiLatProperties xunfeiLatProperties,
                                               SparkIatModelEnum model,
                                               String dwa,
                                               int timeoutSeconds) {
            return IatConfig.builder()
                    .appId(xunfeiLatProperties.getAppId())
                    .apiKey(xunfeiLatProperties.getApiKey())
                    .apiSecret(xunfeiLatProperties.getApiSecret())
                    .model(model)
                    .dwa(dwa)
                    .timeoutSeconds(timeoutSeconds)
                    .build();
        }
    }

    @Autowired
    private SparkIatService sparkIatService;
    
    @Autowired
    private XunfeiLatProperties xunfeiLatProperties;

    @Resource(name = "cpuComputeExecutor")
    private ExecutorService cpuComputeExecutor;

    /**
     * 异步语音转写
     * @param audioFile 音频文件
     * @param config 转写配置
     * @param callback 回调接口
     * @return CompletableFuture
     */
    public CompletableFuture<Void> transcribeAsync(File audioFile, IatConfig config, IatCallback callback) {
        return CompletableFuture.runAsync(() -> {
            try {
                String result = transcribeSync(audioFile, config, callback::onPartialResult);
                callback.onSuccess(result);
            } catch (Exception e) {
                log.error("异步语音转写失败", e);
                callback.onError(e);
            }
        }, cpuComputeExecutor);
    }

    /**
     * 同步语音转写
     * @param audioFile 音频文件
     * @param config 转写配置
     * @return 转写结果
     * @throws Exception 转写异常
     */
    public String transcribeSync(File audioFile, IatConfig config) throws Exception {
        return transcribeSync(audioFile, config, null);
    }

    /**
     * 同步语音转写（带中间结果回调）
     * @param audioFile 音频文件
     * @param config 转写配置
     * @param partialResultCallback 中间结果回调
     * @return 转写结果
     * @throws Exception 转写异常
     */
    public String transcribeSync(File audioFile, IatConfig config, Consumer<String> partialResultCallback) throws Exception {
        return sparkIatService.executeTranscription(audioFile, config, partialResultCallback);
    }



    /**
     * 创建默认配置（从yml配置文件读取）
     * @return 默认IatConfig配置
     */
    public IatConfig createDefaultConfig() {
        return IatConfig.fromProperties(xunfeiLatProperties);
    }
    
    /**
     * 创建自定义配置（从yml配置文件读取基础信息）
     * @param model 语音模型
     * @param dwa 动态修正参数
     * @param timeoutSeconds 超时时间
     * @return 自定义IatConfig配置
     */
    public IatConfig createCustomConfig(SparkIatModelEnum model, String dwa, int timeoutSeconds) {
        return IatConfig.fromProperties(xunfeiLatProperties, model, dwa, timeoutSeconds);
    }
    
    /**
     * 验证音频文件格式
     * @param audioFile 音频文件
     * @return 是否为支持的格式
     */
    public boolean isValidAudioFormat(File audioFile) {
        if (audioFile == null || !audioFile.exists()) {
            return false;
        }
        String fileName = audioFile.getName().toLowerCase();
        return fileName.endsWith(".pcm") || fileName.endsWith(".wav") || 
               fileName.endsWith(".mp3") || fileName.endsWith(".flac");
    }

    /**
     * 获取音频文件信息
     * @param audioFile 音频文件
     * @return 文件信息字符串
     */
    public String getAudioFileInfo(File audioFile) {
        if (audioFile == null || !audioFile.exists()) {
            return "文件不存在";
        }
        return String.format("文件名: %s, 大小: %d bytes, 路径: %s", 
                audioFile.getName(), audioFile.length(), audioFile.getAbsolutePath());
    }
}
