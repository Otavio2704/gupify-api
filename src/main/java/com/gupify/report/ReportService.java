package com.gupify.report;

import com.gupify.exception.ResourceNotFoundException;
import com.gupify.generate.GenerateResponseDto;
import com.gupify.generate.GenerateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportRepository reportRepository;
    private final GenerateService generateService;

    @Transactional(readOnly = true)
    public List<ReportResponseDto> listBySession(UUID sessionId) {
        return reportRepository.findBySessionId(sessionId)
                .stream()
                .map(ReportResponseDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ReportResponseDto getById(UUID reportId, UUID sessionId) {
        Report report = reportRepository.findByIdAndSessionId(reportId, sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Relatório não encontrado"));
        return ReportResponseDto.from(report);
    }

    @Transactional
    public ReportResponseDto update(UUID reportId, UpdateReportRequest request, UUID sessionId) {
        Report report = reportRepository.findByIdAndSessionId(reportId, sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Relatório não encontrado"));

        report.setSummary(request.summary());
        report.setUpdatedAt(LocalDateTime.now());

        Report saved = reportRepository.save(report);
        log.info("Relatório atualizado manualmente. id={}, sessionId={}", reportId, sessionId);
        return ReportResponseDto.from(saved);
    }

    @Transactional
    public GenerateResponseDto regenerate(UUID reportId, UUID sessionId) {
        return generateService.regenerate(reportId, sessionId);
    }

    @Transactional
    public void delete(UUID reportId, UUID sessionId) {
        Report report = reportRepository.findByIdAndSessionId(reportId, sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Relatório não encontrado"));
        reportRepository.delete(report);
        log.info("Relatório removido. id={}, sessionId={}", reportId, sessionId);
    }
}