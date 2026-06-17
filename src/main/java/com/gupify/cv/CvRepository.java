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
     *
     * @param sessionId the owning session's ID
     * @return list of CVs for the session
     */
    List<Cv> findBySessionId(UUID sessionId);

    /**
     * Finds a specific CV by its ID, only if it belongs to the given session.
     *
     * @param id        the CV ID
     * @param sessionId the owning session's ID
     * @return the CV if found and owned by the session
     */
    Optional<Cv> findByIdAndSessionId(UUID id, UUID sessionId);

    /**
     * Deletes a CV by its ID, only if it belongs to the given session.
     *
     * @param id        the CV ID
     * @param sessionId the owning session's ID
     */
    void deleteByIdAndSessionId(UUID id, UUID sessionId);
}
