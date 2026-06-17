package com.gupify.report;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ReportResponseDto(
        UUID id,
        UUID cvId,
        UUID jobDescriptionId,
        String jobDescriptionTitle,
        String summary,
        Integer summaryVersion,
        List<String> keywords,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ReportResponseDto from(Report report) {
        return new ReportResponseDto(
                report.getId(),
                report.getCvId(),
                report.getJobDescriptionId(),
                report.getJobDescriptionTitle(),
                report.getSummary(),
                report.getSummaryVersion(),
                report.getKeywords(),
                report.getCreatedAt(),
                report.getUpdatedAt()
        );
    }
}