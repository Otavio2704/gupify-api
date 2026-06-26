package com.gupify.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class SessionCookieFilter extends OncePerRequestFilter {

    private final SessionRepository sessionRepository;

    @Override
    @Transactional
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        Optional<UUID> sessionIdOpt = extractSessionId(request);

        if (sessionIdOpt.isPresent()) {
            UUID sessionId = sessionIdOpt.get();

            // Atualiza last_seen_at e verifica existência em uma única query.
            // Se retornar 0 linhas afetadas, a sessão não existe no banco.
            int updated = sessionRepository.updateLastSeenAt(sessionId, LocalDateTime.now());

            if (updated > 0) {
                var auth = new UsernamePasswordAuthenticationToken(
                        sessionId,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_SESSION"))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
                log.debug("Sessão autenticada via cookie: {}", sessionId);
            } else {
                log.debug("Cookie presente mas sessão não encontrada no banco: {}", sessionId);
            }
        }

        filterChain.doFilter(request, response);
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
                        log.warn("Valor de cookie inválido recebido");
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }
}
