package com.gupify.generate;

import com.gupify.exception.AiResponseParseException;
import com.gupify.exception.NvidiaRateLimitException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NvidiaAiServiceTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.ChatClientRequestSpec.ChatClientPromptRequestSpec userSpec;

    @Mock
    private ChatClient.CallResponseSpec callSpec;

    private NvidiaAiService nvidiaAiService;

    @BeforeEach
    void setup() {
        when(chatClientBuilder.defaultSystem(any(String.class))).thenReturn(chatClientBuilder);
        when(chatClientBuilder.build()).thenReturn(chatClient);

        nvidiaAiService = new NvidiaAiService(chatClientBuilder, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(nvidiaAiService, "cvMaxChars", 3000);
        ReflectionTestUtils.setField(nvidiaAiService, "jobMaxChars", 2000);
    }

    @Test
    void generate_whenValidResponse_shouldReturnAiResult() {
        AiResult expected = new AiResult("Resumo válido", List.of("Java", "Spring", "REST"));

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.entity(AiResult.class)).thenReturn(expected);

        AiResult result = nvidiaAiService.generate("cv text", "job text");

        assertThat(result.summary()).isEqualTo("Resumo válido");
        assertThat(result.keywords()).hasSize(3);
    }

    @Test
    void generate_whenKeywordsNot3_shouldThrowAiResponseParseException() {
        AiResult invalid = new AiResult("Resumo", List.of("Java", "Spring")); // só 2

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.entity(AiResult.class)).thenReturn(invalid);

        assertThrows(AiResponseParseException.class,
                () -> nvidiaAiService.generate("cv", "job"));
    }

    @Test
    void generate_whenNullResponse_shouldThrowAiResponseParseException() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.entity(AiResult.class)).thenReturn(null);

        assertThrows(AiResponseParseException.class,
                () -> nvidiaAiService.generate("cv", "job"));
    }

    @Test
    void generate_shouldTruncateCvTextWhenExceedsLimit() {
        String longCv = "a".repeat(5000);
        AiResult expected = new AiResult("Resumo", List.of("Java", "Spring", "REST"));

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.entity(AiResult.class)).thenReturn(expected);

        AiResult result = nvidiaAiService.generate(longCv, "job");
        assertThat(result).isNotNull();
    }
}