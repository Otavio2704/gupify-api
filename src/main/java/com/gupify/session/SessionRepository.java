package com.gupify.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Repository
public interface SessionRepository extends JpaRepository<Session, UUID> {

    void deleteByLastSeenAtBefore(LocalDateTime threshold);

    long countByLastSeenAtBefore(LocalDateTime threshold);

    // @Transactional aqui no repository é a forma correta quando o método é chamado
    // de fora de um contexto transacional (como dentro de um filtro Servlet).
    // @Modifying sozinho sem @Transactional lança TransactionRequiredException em runtime.
    @Modifying
    @Transactional
    @Query("UPDATE Session s SET s.lastSeenAt = :now WHERE s.id = :id")
    int updateLastSeenAt(@Param("id") UUID id, @Param("now") LocalDateTime now);
}
