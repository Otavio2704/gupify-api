package com.gupify.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.UUID;

@Repository
public interface SessionRepository extends JpaRepository<Session, UUID> {

    void deleteByLastSeenAtBefore(LocalDateTime threshold);

    long countByLastSeenAtBefore(LocalDateTime threshold);

    /**
     * Atualiza last_seen_at e retorna o número de linhas afetadas.
     * Retorno 0 significa que a sessão não existe — evita um SELECT separado.
     */
    @Modifying
    @Query("UPDATE Session s SET s.lastSeenAt = :now WHERE s.id = :id")
    int updateLastSeenAt(@Param("id") UUID id, @Param("now") LocalDateTime now);
}
