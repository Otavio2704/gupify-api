package com.gupify.generate;

import java.util.List;
import java.util.UUID;

public record GenerateResponseDto(
        UUID reportId,
        String summary,
        List<String> keywords,
        boolean fromCache
) {}