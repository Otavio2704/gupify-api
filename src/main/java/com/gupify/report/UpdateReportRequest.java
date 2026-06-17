package com.gupify.report;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateReportRequest(
        @NotBlank
        @Size(max = 1500, message = "Resumo deve ter no máximo 1500 caracteres")
        String summary
) {}