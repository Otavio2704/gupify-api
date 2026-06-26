package com.gupify.generate;

import com.gupify.cv.CvService;
import com.gupify.exception.ResourceNotFoundException;
import com.gupify.exception.SessionRateLimitException;
import com.gupify.report.Report;
import com.gupify.report.ReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GenerateServiceTest {

    @Mock
    private CvService cvService;

    @Mock
    private NvidiaAiService nvidiaAiService;

    @Mock
    private ReportRepository reportRepository;

    @InjectMocks
    private GenerateService generateService;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(generateService, "generationsPerHour", 10);
    }

    @Test
    void generate_whenCvNotFound_shouldThrowAccessDeniedException() {
        UUID sessionId = UUID.randomUUID();
        GenerateRequest request = new GenerateRequest(
                UUID.randomUUID(), null, "Dev Backend", "Descrição"
        );

        when(cvService.getRawText(any(), any()))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("negado"));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> generateService.generate(request, sessionId));

        verify(nvidiaAiService, never()).generate(any(), any());
    }

    @Test
    void generate_whenCacheHit_shouldReturnCachedReport() {
        UUID sessionId = UUID.randomUUID();
        UUID cvId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        // jobDescriptionId + jobContent presentes: fluxo de cache válido
        GenerateRequest request = new GenerateRequest(cvId, jobId, "Dev Backend", "Descrição da vaga");

        when(cvService.getRawText(cvId, sessionId)).thenReturn("texto cv");
        when(reportRepository.countBySessionIdAndCreatedAtAfter(eq(sessionId), any()))
                .thenReturn(0L);

        Report cached = new Report();
        cached.setId(UUID.randomUUID());
        cached.setSummary("resumo cached");
        cached.setKeywords(List.of("Java", "Spring", "REST"));
        cached.setSummaryVersion(1);

        when(reportRepository.findByCvIdAndJobDescriptionIdAndSessionId(cvId, jobId, sessionId))
                .thenReturn(Optional.of(cached));

        GenerateResponseDto response = generateService.generate(request, sessionId);

        assertThat(response.fromCache()).isTrue();
        assertThat(response.summary()).isEqualTo("resumo cached");
        verify(nvidiaAiService, never()).generate(any(), any());
    }

    @Test
    void generate_whenJobDescriptionIdWithoutJobContent_shouldThrowIllegalArgumentException() {
        UUID sessionId = UUID.randomUUID();
        UUID cvId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        // jobDescriptionId presente, mas sem jobContent: fluxo não implementado
        GenerateRequest request = new GenerateRequest(cvId, jobId, null, null);

        when(cvService.getRawText(cvId, sessionId)).thenReturn("texto cv");
        when(reportRepository.countBySessionIdAndCreatedAtAfter(eq(sessionId), any()))
                .thenReturn(0L);

        assertThrows(IllegalArgumentException.class,
                () -> generateService.generate(request, sessionId));

        verify(nvidiaAiService, never()).generate(any(), any());
    }

    @Test
    void generate_whenRateLimitExceeded_shouldThrowSessionRateLimitException() {
        UUID sessionId = UUID.randomUUID();
        GenerateRequest request = new GenerateRequest(
                UUID.randomUUID(), null, "Dev", "Desc"
        );

        when(cvService.getRawText(any(), any())).thenReturn("texto cv");
        when(reportRepository.countBySessionIdAndCreatedAtAfter(eq(sessionId), any()))
                .thenReturn(10L);

        assertThrows(SessionRateLimitException.class,
                () -> generateService.generate(request, sessionId));

        verify(nvidiaAiService, never()).generate(any(), any());
    }

    @Test
    void generate_whenSuccess_shouldPersistAndReturnReport() {
        UUID sessionId = UUID.randomUUID();
        UUID cvId = UUID.randomUUID();
        GenerateRequest request = new GenerateRequest(cvId, null, "Dev Backend", "Descrição da vaga");

        when(cvService.getRawText(cvId, sessionId)).thenReturn("texto cv");
        when(reportRepository.countBySessionIdAndCreatedAtAfter(eq(sessionId), any()))
                .thenReturn(0L);
        when(nvidiaAiService.generate(any(), any()))
                .thenReturn(new AiResult("Resumo gerado", List.of("Java", "Spring", "PostgreSQL")));
        when(reportRepository.save(any())).thenAnswer(inv -> {
            Report r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        GenerateResponseDto result = generateService.generate(request, sessionId);

        assertThat(result.summary()).isEqualTo("Resumo gerado");
        assertThat(result.keywords()).hasSize(3);
        assertThat(result.fromCache()).isFalse();
        verify(reportRepository).save(any(Report.class));
    }
}
