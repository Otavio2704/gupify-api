package com.gupify.generate;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/generate")
@RequiredArgsConstructor
public class GenerateController {

    private final GenerateService generateService;

    /**
     * POST /api/generate — gera resumo e keywords via IA.
     */
    @PostMapping
    public ResponseEntity<GenerateResponseDto> generate(
            @Valid @RequestBody GenerateRequest request,
            @AuthenticationPrincipal UUID sessionId) {

        GenerateResponseDto response = generateService.generate(request, sessionId);
        return ResponseEntity.ok(response);
    }
}