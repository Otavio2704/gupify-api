package com.gupify.generate;

import com.gupify.cv.CvService;
import com.gupify.exception.ResourceNotFoundException;
import com.gupify.report.Report;
import com.gupify.report.ReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GenerateService {

    private final CvService cvService;
    private final NvidiaAiService nvidiaAiService;
    private final ReportRepository reportRepository;

    @Value("${gupify.rate-limit.generations-per-hour:10}")
    private int generationsPerHour;

    /**
     * Fluxo principal de geração.
     * Verifica cache antes de chamar a IA.
     */
    @Transactional
    public GenerateResponseDto generate(GenerateRequest request, UUID sessionId) {
        // Valida que o CV pertence à sessão (lança AccessDeniedException se não)
        String cvText = cvService.getRawText(request.cvId(), sessionId);

        // Verifica rate limit por sessão
        checkSessionRateLimit(sessionId);

        // Verifica cache: mesmo cv_id + job_description_id já tem relatório?
        if (request.jobDescriptionId() != null) {
            Optional<Report> cached = reportRepository
                    .findByCvIdAndJobDescriptionIdAndSessionId(
                            request.cvId(), request.jobDescriptionId(), sessionId
                    );
            if (cached.isPresent()) {
                Report report = cached.get();
                log.info("Cache hit para cv={} + job={}. Retornando relatório existente id={}",
                        request.cvId(), request.jobDescriptionId(), report.getId());
                return toResponseDto(report, true);
            }
        }

        // Determina o texto da vaga
        String jobText = resolveJobText(request, sessionId);

        // Chama a IA
        log.debug("Chamando NvidiaAiService para sessionId={}", sessionId);
        AiResult result = nvidiaAiService.generate(cvText, jobText);

        // Persiste o relatório
        Report report = buildReport(request, sessionId, result);
        Report saved = reportRepository.save(report);
        log.info("Relatório gerado e salvo. id={}, sessionId={}", saved.getId(), sessionId);

        return toResponseDto(saved, false);
    }

    /**
     * Regenera o resumo para um relatório existente, incrementando summary_version.
     */
    @Transactional
    public GenerateResponseDto regenerate(UUID reportId, UUID sessionId) {
        Report report = reportRepository.findByIdAndSessionId(reportId, sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Relatório não encontrado"));

        checkSessionRateLimit(sessionId);

        String cvText = cvService.getRawText(report.getCvId(), sessionId);
        String jobText = report.getJobDescriptionContent();

        log.debug("Regenerando relatório id={} para sessionId={}", reportId, sessionId);
        AiResult result = nvidiaAiService.generate(cvText, jobText);

        report.setSummary(result.summary());
        report.setKeywords(result.keywords());
        report.setSummaryVersion(report.getSummaryVersion() + 1);
        report.setUpdatedAt(LocalDateTime.now());

        Report saved = reportRepository.save(report);
        log.info("Relatório regenerado. id={}, versão={}", saved.getId(), saved.getSummaryVersion());

        return toResponseDto(saved, false);
    }

    // =========================================================================
    // Métodos privados
    // =========================================================================

    private void checkSessionRateLimit(UUID sessionId) {
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        long recentGenerations = reportRepository.countBySessionIdAndCreatedAtAfter(sessionId, oneHourAgo);

        if (recentGenerations >= generationsPerHour) {
            log.warn("Rate limit por sessão atingido. sessionId={}, geracoes={}", sessionId, recentGenerations);
            throw new com.gupify.exception.SessionRateLimitException(
                    "Limite de " + generationsPerHour + " gerações por hora atingido. Aguarde antes de tentar novamente."
            );
        }
    }

    private String resolveJobText(GenerateRequest request, UUID sessionId) {
        if (request.jobContent() != null && !request.jobContent().isBlank()) {
            return request.jobTitle() + "\n\n" + request.jobContent();
        }
        throw new IllegalArgumentException("Texto da vaga não informado");
    }

    private Report buildReport(GenerateRequest request, UUID sessionId, AiResult result) {
        Report report = new Report();
        report.setSessionId(sessionId);
        report.setCvId(request.cvId());
        report.setJobDescriptionId(request.jobDescriptionId());
        report.setJobDescriptionTitle(request.jobTitle());
        report.setJobDescriptionContent(request.jobContent());
        report.setSummary(result.summary());
        report.setKeywords(result.keywords());
        report.setSummaryVersion(1);
        return report;
    }

    private GenerateResponseDto toResponseDto(Report report, boolean fromCache) {
        return new GenerateResponseDto(
                report.getId(),
                report.getSummary(),
                report.getKeywords(),
                fromCache
        );
    }
}