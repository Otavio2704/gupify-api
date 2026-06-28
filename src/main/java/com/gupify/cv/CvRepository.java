package com.gupify.cv;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Cv} entities.
 *
 * <p>All custom queries are scoped by session to enforce data isolation.</p>
 */
@Repository
public interface CvRepository extends JpaRepository<Cv, UUID> {

    /**
     * Finds all CVs belonging to the given session.
     */
    List<Cv> findBySessionId(UUID sessionId);

    /**
     * Finds a specific CV by its ID, only if it belongs to the given session.
     */
    Optional<Cv> findByIdAndSessionId(UUID id, UUID sessionId);

    // FIX #4 — Necessário para checar o limite de CVs por sessão antes do upload.
    long countBySessionId(UUID sessionId);
}
