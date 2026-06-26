package com.gupify.session;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    public static final String COOKIE_NAME = "gupify_session";

    private final SessionRepository sessionRepository;

    @Value("${session.cookie.max-age-days:90}")
    private int cookieMaxAgeDays;

    @Value("${session.expiry-days:180}")
    private int sessionExpiryDays;

    @Transactional
    public Optional<Session> findAndRefresh(UUID sessionId) {
        return sessionRepository.findById(sessionId).map(session -> {
            session.setLastSeenAt(LocalDateTime.now());
            return sessionRepository.save(session);
        });
    }

    @Transactional
    public Session createSession() {
        Session session = new Session(UUID.randomUUID());
        Session saved = sessionRepository.save(session);
        log.info("Nova sessão criada: {}", saved.getId());
        return saved;
    }

    @Transactional
    public void invalidateSession(UUID sessionId) {
        sessionRepository.deleteById(sessionId);
        log.info("Sessão invalidada: {}", sessionId);
    }

    public ResponseCookie buildCookie(UUID sessionId) {
        return ResponseCookie.from(COOKIE_NAME, sessionId.toString())
                .httpOnly(true)
                .secure(false) // Alterado para false para permitir rodar em localhost (HTTP)
                .sameSite("Lax") // Alterado para Lax para permitir envio de cookies via HTTP
                .maxAge(Duration.ofDays(cookieMaxAgeDays))
                .path("/")
                .build();
    }

    public ResponseCookie buildExpiredCookie() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(false) // Alterado para false
                .sameSite("Lax") // Alterado para Lax
                .maxAge(Duration.ZERO)
                .path("/")
                .build();
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanExpiredSessions() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(sessionExpiryDays);
        long count = sessionRepository.countByLastSeenAtBefore(threshold);
        sessionRepository.deleteByLastSeenAtBefore(threshold);
        log.info("Limpeza de sessões concluída. Removidas: {}, threshold: {}", count, threshold);
    }
}
