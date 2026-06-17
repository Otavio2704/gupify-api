package com.gupify.cv;

import com.gupify.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CvServiceTest {

    @Mock
    private CvRepository cvRepository;

    @InjectMocks
    private CvService cvService;

    @Test
    void listBySession_shouldReturnOnlyCvsOfSession() {
        UUID sessionId = UUID.randomUUID();
        Cv cv = new Cv();
        cv.setSessionId(sessionId);
        cv.setFileName("curriculo.pdf");
        cv.setFileType("PDF");

        when(cvRepository.findBySessionId(sessionId)).thenReturn(List.of(cv));

        List<CvResponseDto> result = cvService.listBySession(sessionId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).fileName()).isEqualTo("curriculo.pdf");
    }

    @Test
    void delete_whenCvNotFound_shouldThrowResourceNotFoundException() {
        UUID cvId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(cvRepository.findByIdAndSessionId(cvId, sessionId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> cvService.delete(cvId, sessionId));
        verify(cvRepository, never()).delete(any());
    }

    @Test
    void delete_whenCvBelongsToSession_shouldDelete() {
        UUID cvId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Cv cv = new Cv();
        cv.setSessionId(sessionId);

        when(cvRepository.findByIdAndSessionId(cvId, sessionId)).thenReturn(Optional.of(cv));

        cvService.delete(cvId, sessionId);

        verify(cvRepository).delete(cv);
    }

    @Test
    void getRawText_whenCvBelongsToSession_shouldReturnText() {
        UUID cvId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Cv cv = new Cv();
        cv.setRawText("texto do cv");
        cv.setSessionId(sessionId);

        when(cvRepository.findByIdAndSessionId(cvId, sessionId)).thenReturn(Optional.of(cv));

        String text = cvService.getRawText(cvId, sessionId);

        assertThat(text).isEqualTo("texto do cv");
    }

    @Test
    void getRawText_whenCvDoesNotBelongToSession_shouldThrowAccessDeniedException() {
        UUID cvId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        when(cvRepository.findByIdAndSessionId(cvId, sessionId)).thenReturn(Optional.empty());

        assertThrows(AccessDeniedException.class, () -> cvService.getRawText(cvId, sessionId));
    }
}