package com.hewei.hzyjy.xunzhi.media.api;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.hewei.hzyjy.xunzhi.common.web.GlobalExceptionHandler;
import com.hewei.hzyjy.xunzhi.media.application.WebSocketMessageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebSocketControllerTest {

    private MockMvc mockMvc;
    private WebSocketMessageService messageService;
    private SaTokenContext originalContext;
    private SaTokenConfig originalConfig;
    private SaTokenDao originalDao;
    private StpInterface originalRoles;
    private SaTokenDaoDefaultImpl testDao;
    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        originalContext = SaManager.getSaTokenContext();
        originalConfig = SaManager.getConfig();
        originalDao = SaManager.getSaTokenDao();
        originalRoles = SaManager.getStpInterface();
        testDao = new SaTokenDaoDefaultImpl();
        SaManager.setConfig(new SaTokenConfig().setTokenName("Authorization").setTokenPrefix("Bearer")
                .setIsPrint(false).setIsLog(false));
        SaManager.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
        SaManager.setSaTokenDao(testDao);
        StpInterface roles = mock(StpInterface.class);
        when(roles.getRoleList("admin", "login")).thenReturn(List.of("admin"));
        when(roles.getRoleList("ordinary-user", "login")).thenReturn(List.of("user"));
        SaManager.setStpInterface(roles);
        adminToken = StpUtil.getStpLogic().createLoginSession("admin");
        userToken = StpUtil.getStpLogic().createLoginSession("ordinary-user");

        messageService = mock(WebSocketMessageService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new WebSocketController(messageService))
                .addInterceptors(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        testDao.destroy();
        SaManager.setSaTokenContext(originalContext);
        SaManager.setConfig(originalConfig);
        SaManager.setSaTokenDao(originalDao);
        SaManager.setStpInterface(originalRoles);
    }

    @ParameterizedTest
    @ValueSource(strings = {"send-message", "notification", "transcription", "error", "status"})
    void shouldRejectOrdinaryUsersBeforeCallingPushService(String endpoint) throws Exception {
        mockMvc.perform(request(endpoint).header(StpUtil.getTokenName(), "Bearer " + userToken))
                .andExpect(result -> assertInstanceOf(NotRoleException.class, result.getResolvedException()));

        verifyNoInteractions(messageService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"send-message", "notification", "transcription", "error", "status"})
    void shouldRejectAnonymousRequestsBeforeCallingPushService(String endpoint) throws Exception {
        mockMvc.perform(request(endpoint))
                .andExpect(result -> assertInstanceOf(NotLoginException.class, result.getResolvedException()));

        verifyNoInteractions(messageService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"send-message", "notification", "transcription", "error", "status"})
    void shouldAllowAdminsToManageOtherUsersConnections(String endpoint) throws Exception {
        mockMvc.perform(request(endpoint).header(StpUtil.getTokenName(), "Bearer " + adminToken))
                .andExpect(status().isOk());

        switch (endpoint) {
            case "send-message" -> verify(messageService).sendMessageToUser("9", "system_notification", "notice", "payload");
            case "notification" -> verify(messageService).sendSystemNotification("9", "notice");
            case "transcription" -> verify(messageService).sendTranscriptionResult("9", "recognized text", true);
            case "error" -> verify(messageService).sendErrorMessage("9", "failed");
            case "status" -> verify(messageService).isUserOnline("9");
            default -> throw new IllegalArgumentException(endpoint);
        }
    }

    private MockHttpServletRequestBuilder request(String endpoint) {
        String base = "/api/xunzhi/v1/websocket/";
        return switch (endpoint) {
            case "send-message" -> post(base + "send-message").param("userId", "9")
                    .param("type", "system_notification").param("message", "notice").param("data", "payload");
            case "notification" -> post(base + "notification/9").param("message", "notice");
            case "transcription" -> post(base + "transcription/9").param("result", "recognized text").param("isFinal", "true");
            case "error" -> post(base + "error/9").param("errorMessage", "failed");
            case "status" -> get(base + "user/9/status");
            default -> throw new IllegalArgumentException(endpoint);
        };
    }
}
