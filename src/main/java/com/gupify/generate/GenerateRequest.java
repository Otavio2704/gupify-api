package com.gupify.generate;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record GenerateRequest(
        @NotNull UUID cvId,

        // Opção 1: vaga já persistida
        UUID jobDescriptionId,

        // Opção 2: vaga inline
        String jobTitle,
        String jobContent
) {}