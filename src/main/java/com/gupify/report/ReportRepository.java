package com.gupify.report;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReportRepository extends JpaRepository<Report, UUID> {

    List<Report> findBySessionId(UUID sessionId);

    Optional<Report> findByIdAndSessionId(UUID id, UUID sessionId);

    Optional<Report> findByCvIdAndJobDescriptionIdAndSessionId(
            UUID cvId, UUID jobDescriptionId, UUID sessionId
    );

    long countBySessionIdAndCreatedAtAfter(UUID sessionId, LocalDateTime after);
}