package com.gupify.generate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record GenerateRequest(
        @NotNull(message = "cvId é obrigatório")
        UUID cvId,

        // Opção 1: vaga já persistida
        UUID jobDescriptionId,

        // Opção 2: vaga inline
        @Size(max = 200, message = "Título da vaga deve ter no máximo 200 caracteres")
        String jobTitle,

        @Size(max = 20000, message = "Descrição da vaga deve ter no máximo 20000 caracteres")
        String jobContent
) {
    public GenerateRequest {
        boolean temVagaInline = jobContent != null && !jobContent.isBlank();
        boolean temVagaPersistida = jobDescriptionId != null;

        if (!temVagaInline && !temVagaPersistida) {
            throw new IllegalArgumentException(
                    "Informe jobDescriptionId ou jobContent para gerar o resumo"
            );
        }
    }
}