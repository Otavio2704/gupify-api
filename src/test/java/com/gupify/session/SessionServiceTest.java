package com.gupify.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock
    private SessionRepository sessionRepository;

    @InjectMocks
    private SessionService sessionService;

    @Test
    void findAndRefresh_whenSessionExists_shouldUpdateLastSeenAt() {
        UUID id = UUID.randomUUID();
        Session session = new Session(id);
        session.setLastSeenAt(LocalDateTime.now().minusHours(1));

        when(sessionRepository.findById(id)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<Session> result = sessionService.findAndRefresh(id);

        assertThat(result).isPresent();
        verify(sessionRepository).save(session);
    }

    @Test
    void findAndRefresh_whenSessionNotFound_shouldReturnEmpty() {
        UUID id = UUID.randomUUID();
        when(sessionRepository.findById(id)).thenReturn(Optional.empty());

        Optional<Session> result = sessionService.findAndRefresh(id);

        assertThat(result).isEmpty();
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void createSession_shouldPersistAndReturnSession() {
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Session result = sessionService.createSession();

        assertThat(result).isNotNull();
        assertThat(result.getId()).isNotNull();
        verify(sessionRepository).save(any(Session.class));
    }

    @Test
    void invalidateSession_shouldDeleteFromRepository() {
        UUID id = UUID.randomUUID();
        doNothing().when(sessionRepository).deleteById(id);

        sessionService.invalidateSession(id);

        verify(sessionRepository).deleteById(id);
    }

    @Test
    void buildCookie_shouldReturnHttpOnlyCookie() {
        UUID id = UUID.randomUUID();
        var cookie = sessionService.buildCookie(id);

        assertThat(cookie.getName()).isEqualTo(SessionService.COOKIE_NAME);
        assertThat(cookie.getValue()).isEqualTo(id.toString());
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
    }

    @Test
    void updateLastSeenAt_whenSessionExists_shouldReturnOne() {
        UUID id = UUID.randomUUID();
        when(sessionRepository.updateLastSeenAt(eq(id), any(LocalDateTime.class))).thenReturn(1);

        int affected = sessionRepository.updateLastSeenAt(id, LocalDateTime.now());

        assertThat(affected).isEqualTo(1);
    }

    @Test
    void updateLastSeenAt_whenSessionNotExists_shouldReturnZero() {
        UUID id = UUID.randomUUID();
        when(sessionRepository.updateLastSeenAt(eq(id), any(LocalDateTime.class))).thenReturn(0);

        int affected = sessionRepository.updateLastSeenAt(id, LocalDateTime.now());

        assertThat(affected).isEqualTo(0);
    }
}
