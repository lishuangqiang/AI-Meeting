package com.hewei.hzyjy.xunzhi.interview.api;

import com.hewei.hzyjy.xunzhi.common.convention.annotation.CurrentUser;
import com.hewei.hzyjy.xunzhi.common.convention.context.UserContext;
import com.hewei.hzyjy.xunzhi.common.convention.exception.ClientException;
import com.hewei.hzyjy.xunzhi.common.convention.exception.ServiceException;
import com.hewei.hzyjy.xunzhi.interview.flow.session.InterviewSessionFacade;
import com.hewei.hzyjy.xunzhi.interview.flow.report.InterviewResumePreviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@Slf4j
@RestController
@RequestMapping("/api/xunzhi/v1/interview")
@RequiredArgsConstructor
public class InterviewResumeController {

    private final InterviewSessionFacade interviewSessionFacade;

    @GetMapping(value = "/sessions/{sessionId}/resume/preview", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> previewResume(
            @PathVariable String sessionId,
            @CurrentUser UserContext currentUser) {
        try {
            InterviewResumePreviewService.ResumePreviewResource resource =
                    interviewSessionFacade.loadResumePreview(sessionId, currentUser.getUserId());

            MediaType contentType = MediaType.APPLICATION_PDF;
            try {
                contentType = MediaType.parseMediaType(resource.getContentType());
            } catch (Exception ex) {
                log.warn("Failed to parse resume preview content type, sessionId={}, contentType={}",
                        sessionId, resource.getContentType());
            }

            return ResponseEntity.ok()
                    .contentType(contentType)
                    .contentLength(resource.getContent().length)
                    .cacheControl(CacheControl.noStore().mustRevalidate())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.inline()
                                    .filename(resource.getFileName(), StandardCharsets.UTF_8)
                                    .build()
                                    .toString())
                    .body(resource.getContent());
        } catch (ClientException ex) {
            log.warn("Resume preview failed, sessionId={}, message={}", sessionId, ex.getErrorMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                    .body(ex.getErrorMessage().getBytes(StandardCharsets.UTF_8));
        } catch (ServiceException ex) {
            log.warn("Resume preview failed, sessionId={}, message={}", sessionId, ex.getErrorMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                    .body(ex.getErrorMessage().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            log.error("Resume preview failed unexpectedly, sessionId={}", sessionId, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                    .body("Failed to preview resume".getBytes(StandardCharsets.UTF_8));
        }
    }
}
