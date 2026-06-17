package com.gupify.cv;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/cv")
@RequiredArgsConstructor
public class CvController {

    private final CvService cvService;

    /**
     * POST /api/cv/upload — recebe PDF ou DOCX, extrai texto, persiste e retorna cv_id.
     */
    @PostMapping("/upload")
    public ResponseEntity<CvResponseDto> upload(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UUID sessionId) throws IOException {

        CvResponseDto response = cvService.upload(file, sessionId);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/cv — lista CVs da sessão (sem rawText).
     */
    @GetMapping
    public ResponseEntity<List<CvResponseDto>> list(
            @AuthenticationPrincipal UUID sessionId) {

        return ResponseEntity.ok(cvService.listBySession(sessionId));
    }

    /**
     * DELETE /api/cv/{id} — remove um CV da sessão.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID sessionId) {

        cvService.delete(id, sessionId);
        return ResponseEntity.noContent().build();
    }
}