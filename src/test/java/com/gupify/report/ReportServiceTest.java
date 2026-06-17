package com.gupify.report;

import com.gupify.exception.ResourceNotFoundException;
import com.gupify.generate.GenerateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private GenerateService generateService;

    @InjectMocks
    private ReportService reportService;

    @Test
    void listBySession_shouldReturnOnlySessionReports() {
        UUID sessionId = UUID.randomUUID();
        Report report = new Report();
        report.setSessionId(sessionId);
        report.setSummary("resumo");
        report.setKeywords(List.of("Java", "Spring", "REST"));
        report.setSummaryVersion(1);

        when(reportRepository.findBySessionId(sessionId)).thenReturn(List.of(report));

        List<ReportResponseDto> result = reportService.listBySession(sessionId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).summary()).isEqualTo("resumo");
    }

    @Test
    void getById_whenNotFound_shouldThrowResourceNotFoundException() {
        UUID id = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        when(reportRepository.findByIdAndSessionId(id, sessionId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> reportService.getById(id, sessionId));
    }

    @Test
    void update_whenReportFound_shouldUpdateSummary() {
        UUID id = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Report report = new Report();
        report.setId(id);
        report.setSummary("antigo resumo");
        report.setKeywords(List.of("Java", "Spring", "REST"));
        report.setSummaryVersion(1);

        when(reportRepository.findByIdAndSessionId(id, sessionId)).thenReturn(Optional.of(report));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateReportRequest request = new UpdateReportRequest("novo resumo");
        ReportResponseDto result = reportService.update(id, request, sessionId);

        assertThat(result.summary()).isEqualTo("novo resumo");
        verify(reportRepository).save(report);
    }

    @Test
    void delete_whenNotFound_shouldThrowResourceNotFoundException() {
        UUID id = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        when(reportRepository.findByIdAndSessionId(id, sessionId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> reportService.delete(id, sessionId));

        verify(reportRepository, never()).delete(any());
    }

    @Test
    void delete_whenFound_shouldDelete() {
        UUID id = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Report report = new Report();

        when(reportRepository.findByIdAndSessionId(id, sessionId)).thenReturn(Optional.of(report));

        reportService.delete(id, sessionId);

        verify(reportRepository).delete(report);
    }
}