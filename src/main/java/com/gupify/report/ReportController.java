package com.gupify.report;

import com.gupify.generate.GenerateResponseDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @GetMapping
    public ResponseEntity<List<ReportResponseDto>> list(
            @AuthenticationPrincipal UUID sessionId) {
        return ResponseEntity.ok(reportService.listBySession(sessionId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReportResponseDto> getById(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID sessionId) {
        return ResponseEntity.ok(reportService.getById(id, sessionId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ReportResponseDto> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateReportRequest request,
            @AuthenticationPrincipal UUID sessionId) {
        return ResponseEntity.ok(reportService.update(id, request, sessionId));
    }

    @PostMapping("/{id}/regenerate")
    public ResponseEntity<GenerateResponseDto> regenerate(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID sessionId) {
        return ResponseEntity.ok(reportService.regenerate(id, sessionId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID sessionId) {
        reportService.delete(id, sessionId);
        return ResponseEntity.noContent().build();
    }
}