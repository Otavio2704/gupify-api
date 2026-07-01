package com.gupify.session;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "sessions")
@Getter
@Setter
@NoArgsConstructor
public class Session {

    private static final int DEFAULT_EXPIRY_DAYS = 180;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    // Mapeamento da coluna expires_at (NOT NULL no schema do Supabase).
    // Sem esse campo, o INSERT falha por violação de constraint em produção.
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.lastSeenAt = now;
        if (this.expiresAt == null) {
            this.expiresAt = now.plusDays(DEFAULT_EXPIRY_DAYS);
        }
    }

    public Session(UUID id) {
        this.id = id;
    }
}
