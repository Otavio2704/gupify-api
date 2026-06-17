package com.gupify.cv;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO de resposta — nunca expõe o rawText do CV.
 */
public record CvResponseDto(
        UUID id,
        String fileName,
        String fileType,
        LocalDateTime createdAt
) {
    public static CvResponseDto from(Cv cv) {
        return new CvResponseDto(cv.getId(), cv.getFileName(), cv.getFileType(), cv.getCreatedAt());
    }
}