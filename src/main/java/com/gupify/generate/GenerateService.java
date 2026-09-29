package com.gupify.generate;

import com.gupify.cv.CvService;
import com.gupify.exception.ResourceNotFoundException;
import com.gupify.exception.SessionRateLimitException;
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

    // =========================================================================
    // STREAMING — usado pelo endpoint /api/generate/stream
    //
    // O fluxo antigo (generate()) mantém @Transactional em volta da chamada de IA,
    // o que prende uma conexão de banco durante os minutos de geração. Aqui a
    // lógica é dividida em duas transações curtas:
    //
    //   1) preparar()  → valida, aplica rate limit e resolve os textos (readOnly)
    //   2) salvar()    → grava o resultado no fim do stream (transação curta)
    //
    // A chamada da IA acontece ENTRE as duas, sem transação aberta.
    // =========================================================================

    /** Textos já validados/prontos para enviar à IA. */
    public record ContextoGeracao(String cvText, String jobText) {
    }

    @Transactional(readOnly = true)
    public ContextoGeracao preparar(GenerateRequest request, UUID sessionId) {
        String cvText = cvService.getRawText(request.cvId(), sessionId);
        checkSessionRateLimit(sessionId);

        if (request.jobDescriptionId() != null
                && (request.jobContent() == null || request.jobContent().isBlank())) {
            log.warn("jobDescriptionId={} informado sem jobContent no fluxo de streaming.",
                    request.jobDescriptionId());
            throw new IllegalArgumentException(
                    "O fluxo por jobDescriptionId ainda não está disponível. "
                            + "Informe o conteúdo da vaga no campo jobContent."
            );
        }

        return new ContextoGeracao(cvText, resolveJobText(request));
    }

    /** Grava o resultado do streaming. Chamado no fim do stream, fora da IA. */
    @Transactional
    public GenerateResponseDto salvar(GenerateRequest request, UUID sessionId,
                                      String summary, java.util.List<String> keywords) {
        Report report = buildReport(request, sessionId, new AiResult(summary, keywords));
        Report saved = reportRepository.save(report);
        log.info("Relatório gerado por streaming e salvo. id={}, sessionId={}", saved.getId(), sessionId);
        return toResponseDto(saved, false);
    }

    @Transactional
    public GenerateResponseDto generate(GenerateRequest request, UUID sessionId) {
        String cvText = cvService.getRawText(request.cvId(), sessionId);

        checkSessionRateLimit(sessionId);

        if (request.jobDescriptionId() != null) {
            // Só tenta cache se também tiver o conteúdo inline, pois o fluxo
            // de JobDescription persistido ainda não está implementado.
            // Quando estiver, remover essa guarda e buscar o texto pelo jobDescriptionId.
            if (request.jobContent() == null || request.jobContent().isBlank()) {
                log.warn(
                    "jobDescriptionId={} informado sem jobContent. " +
                    "O fluxo de vaga persistida ainda não está implementado. " +
                    "Informe jobContent para continuar.",
                    request.jobDescriptionId()
                );
                throw new IllegalArgumentException(
                    "O fluxo por jobDescriptionId ainda não está disponível. " +
                    "Informe o conteúdo da vaga no campo jobContent."
                );
            }

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

        String jobText = resolveJobText(request);

        log.debug("Chamando NvidiaAiService para sessionId={}", sessionId);
        AiResult result = nvidiaAiService.generate(cvText, jobText);

        Report report = buildReport(request, sessionId, result);
        Report saved = reportRepository.save(report);
        log.info("Relatório gerado e salvo. id={}, sessionId={}", saved.getId(), sessionId);

        return toResponseDto(saved, false);
    }

    @Transactional
    public GenerateResponseDto regenerate(UUID reportId, UUID sessionId) {
        Report report = reportRepository.findByIdAndSessionId(reportId, sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Relatório não encontrado"));

        checkSessionRateLimit(sessionId);

        String cvText = cvService.getRawText(report.getCvId(), sessionId);
        String jobText = report.getJobDescriptionContent();

        if (jobText == null || jobText.isBlank()) {
            throw new IllegalArgumentException(
                "Conteúdo da vaga ausente no relatório. Não é possível regenerar."
            );
        }

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
    // Privado
    // =========================================================================

    private void checkSessionRateLimit(UUID sessionId) {
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        long recentGenerations = reportRepository.countBySessionIdAndCreatedAtAfter(sessionId, oneHourAgo);

        if (recentGenerations >= generationsPerHour) {
            log.warn("Rate limit por sessão atingido. sessionId={}, geracoes={}", sessionId, recentGenerations);
            throw new SessionRateLimitException(
                    "Limite de " + generationsPerHour + " gerações por hora atingido. Aguarde antes de tentar novamente."
            );
        }
    }

    private String resolveJobText(GenerateRequest request) {
        if (request.jobContent() != null && !request.jobContent().isBlank()) {
            String title = (request.jobTitle() != null && !request.jobTitle().isBlank())
                    ? request.jobTitle() + "\n\n"
                    : "";
            return title + request.jobContent();
        }
        // Nunca deve chegar aqui: o GenerateRequest valida no compact constructor
        // e o bloco do jobDescriptionId acima já lança antes de chegar neste ponto.
        throw new IllegalArgumentException(
            "Texto da vaga não informado."
        );
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
