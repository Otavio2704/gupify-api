package com.gupify.session;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
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

    /**
     * Verifica se uma sessão existe e é válida no banco.
     */
    @Transactional
    public Optional<Session> findAndRefresh(UUID sessionId) {
        return sessionRepository.findById(sessionId).map(session -> {
            session.setLastSeenAt(LocalDateTime.now());
            return sessionRepository.save(session);
        });
    }

    /**
     * Cria uma nova sessão e retorna a entidade persistida.
     */
    @Transactional
    public Session createSession() {
        Session session = new Session(UUID.randomUUID());
        Session saved = sessionRepository.save(session);
        log.info("Nova sessão criada: {}", saved.getId());
        return saved;
    }

    /**
     * Invalida (deleta) a sessão do banco.
     */
    @Transactional
    public void invalidateSession(UUID sessionId) {
        sessionRepository.deleteById(sessionId);
        log.info("Sessão invalidada: {}", sessionId);
    }

    /**
     * Constrói o ResponseCookie HttpOnly com as configurações de segurança.
     */
    public ResponseCookie buildCookie(UUID sessionId) {
        return ResponseCookie.from(COOKIE_NAME, sessionId.toString())
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .maxAge(Duration.ofDays(cookieMaxAgeDays))
                .path("/")
                .build();
    }

    /**
     * Constrói cookie de expiração (para invalidar no browser).
     */
    public ResponseCookie buildExpiredCookie() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .maxAge(Duration.ZERO)
                .path("/")
                .build();
    }

    /**
     * Job agendado para limpar sessões expiradas (executa às 03:00 todo dia).
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanExpiredSessions() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(sessionExpiryDays);
        log.info("Limpando sessões com last_seen_at anterior a {}", threshold);
        sessionRepository.deleteByLastSeenAtBefore(threshold);
    }
}