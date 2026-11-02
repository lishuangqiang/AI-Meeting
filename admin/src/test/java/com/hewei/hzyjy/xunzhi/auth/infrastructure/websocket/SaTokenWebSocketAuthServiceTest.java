package com.hewei.hzyjy.xunzhi.auth.infrastructure.websocket;

import com.hewei.hzyjy.xunzhi.auth.application.CurrentUserService;
import jakarta.websocket.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SaTokenWebSocketAuthServiceTest {

    @Test
    void shouldAuthorizeWhenTokenMapsToSameUserId() {
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        Session session = mock(Session.class);
        when(session.getRequestParameterMap()).thenReturn(Map.of("token", List.of("Bearer token-1")));
        when(currentUserService.isValidToken("token-1")).thenReturn(true);
        when(currentUserService.getUsernameByToken("token-1")).thenReturn("alice");
        when(currentUserService.getUserIdByUsername("alice")).thenReturn(9L);

        SaTokenWebSocketAuthService authService = new SaTokenWebSocketAuthService(currentUserService);

        assertTrue(authService.isAuthorized(session, "9"));
    }

    @Test
    void shouldRejectWhenTokenIsMissingOrInvalid() {
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        Session session = mock(Session.class);
        when(session.getRequestParameterMap()).thenReturn(Map.of("token", List.of("bad-token")));
        when(currentUserService.isValidToken("bad-token")).thenReturn(false);

        SaTokenWebSocketAuthService authService = new SaTokenWebSocketAuthService(currentUserService);

        assertFalse(authService.isAuthorized(session, "9"));
    }

    @Test
    void shouldRejectNumericUsernameMatchingAnotherUsersId() {
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        Session session = mock(Session.class);
        when(session.getRequestParameterMap()).thenReturn(Map.of("token", List.of("token-1")));
        when(currentUserService.isValidToken("token-1")).thenReturn(true);
        when(currentUserService.getUsernameByToken("token-1")).thenReturn("9");
        when(currentUserService.getUserIdByUsername("9")).thenReturn(42L);

        SaTokenWebSocketAuthService authService = new SaTokenWebSocketAuthService(currentUserService);

        assertFalse(authService.isAuthorized(session, "9"));
        assertTrue(authService.isAuthorized(session, "42"));
    }

    @Test
    void shouldRejectUsernamePathEvenWhenItMatchesTokenUsername() {
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        Session session = mock(Session.class);
        when(session.getRequestParameterMap()).thenReturn(Map.of("token", List.of("token-1")));
        when(currentUserService.isValidToken("token-1")).thenReturn(true);
        when(currentUserService.getUsernameByToken("token-1")).thenReturn("alice");
        when(currentUserService.getUserIdByUsername("alice")).thenReturn(9L);

        assertFalse(new SaTokenWebSocketAuthService(currentUserService).isAuthorized(session, "alice"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"token", "Authorization", "authorization", "access_token", "satoken"})
    void shouldPreserveSupportedTokenParameters(String parameter) {
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        Session session = mock(Session.class);
        when(session.getRequestParameterMap()).thenReturn(Map.of(parameter, List.of(" bearer token-1 ")));
        when(currentUserService.isValidToken("token-1")).thenReturn(true);
        when(currentUserService.getUsernameByToken("token-1")).thenReturn("alice");
        when(currentUserService.getUserIdByUsername("alice")).thenReturn(9L);

        SaTokenWebSocketAuthService authService = new SaTokenWebSocketAuthService(currentUserService);

        assertTrue(authService.isAuthorized(session, "9"));
        assertFalse(authService.isAuthorized(session, "10"));
    }

    @Test
    void shouldRejectMissingToken() {
        Session session = mock(Session.class);
        when(session.getRequestParameterMap()).thenReturn(Map.of());
        assertFalse(new SaTokenWebSocketAuthService(mock(CurrentUserService.class)).isAuthorized(session, "9"));
    }

    @Test
    void shouldRejectWhenUserIdLookupFails() {
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        Session session = mock(Session.class);
        when(session.getRequestParameterMap()).thenReturn(Map.of("token", List.of("token-1")));
        when(currentUserService.isValidToken("token-1")).thenReturn(true);
        when(currentUserService.getUsernameByToken("token-1")).thenReturn("alice");
        when(currentUserService.getUserIdByUsername("alice")).thenThrow(new IllegalArgumentException("user missing"));

        assertFalse(new SaTokenWebSocketAuthService(currentUserService).isAuthorized(session, "9"));
    }
}
