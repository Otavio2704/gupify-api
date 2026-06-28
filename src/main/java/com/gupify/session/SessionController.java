package com.gupify.session;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/session")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    /**
     * POST /api/session — cria ou confirma uma sessão.
     * Se o cookie já for válido, apenas atualiza last_seen_at.
     * Se inválido/ausente, cria nova sessão e devolve cookie.
     *
     * FIX #5 — Rate limit de 5 criações por minuto para evitar session flooding.
     * Sessões já existentes confirmadas via cookie passam direto sem consumir o limite.
     */
    @PostMapping
    @RateLimiter(name = "session-create", fallbackMethod = "sessionCreateRateLimitFallback")
    public ResponseEntity<Map<String, String>> createOrConfirmSession(HttpServletRequest request) {
        Optional<UUID> existingSessionId = extractSessionId(request);

        if (existingSessionId.isPresent()) {
            Optional<Session> existing = sessionService.findAndRefresh(existingSessionId.get());
            if (existing.isPresent()) {
                log.debug("Sessão existente confirmada: {}", existing.get().getId());
                return ResponseEntity.ok(Map.of("status", "session_confirmed"));
            }
        }

        Session newSession = sessionService.createSession();
        var cookie = sessionService.buildCookie(newSession.getId());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(Map.of("status", "session_created"));
    }

    /**
     * GET /api/session — valida se a sessão do cookie é válida.
     * Retorna 200 se válida, 401 se inválida (tratado pelo filtro).
     */
    @GetMapping
    public ResponseEntity<Map<String, String>> validateSession() {
        return ResponseEntity.ok(Map.of("status", "valid"));
    }

    /**
     * DELETE /api/session — invalida a sessão e limpa o cookie.
     */
    @DeleteMapping
    public ResponseEntity<Map<String, String>> deleteSession(HttpServletRequest request) {
        extractSessionId(request).ifPresent(sessionService::invalidateSession);
        var expiredCookie = sessionService.buildExpiredCookie();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredCookie.toString())
                .body(Map.of("status", "session_invalidated"));
    }

    // FIX #5 — Fallback acionado quando o rate limiter bloqueia novas criações de sessão
    private ResponseEntity<Map<String, String>> sessionCreateRateLimitFallback(
            HttpServletRequest request, Exception ex) {
        log.warn("Rate limit de criação de sessão atingido. ip={}", request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", "Muitas requisições. Aguarde antes de tentar novamente."));
    }

    private Optional<UUID> extractSessionId(HttpServletRequest request) {
        if (request.getCookies() == null) return Optional.empty();
        return Arrays.stream(request.getCookies())
                .filter(c -> SessionService.COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .map(value -> {
                    try {
                        return UUID.fromString(value);
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }
}
