package com.gupify.generate;

import com.gupify.exception.NvidiaRateLimitException;
import com.gupify.exception.ResourceNotFoundException;
import com.gupify.exception.SessionRateLimitException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

@Slf4j
@RestController
@RequestMapping("/api/generate")
@RequiredArgsConstructor
public class GenerateController {

    private final GenerateService generateService;
    private final NvidiaAiService nvidiaAiService;

    /**
     * POST /api/generate — gera resumo e keywords via IA (modo clássico).
     * O cliente espera tudo chegar para receber a resposta.
     */
    @PostMapping
    public ResponseEntity<GenerateResponseDto> generate(
            @Valid @RequestBody GenerateRequest request,
            @AuthenticationPrincipal UUID sessionId) {

        GenerateResponseDto response = generateService.generate(request, sessionId);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/generate/stream — mesma geração, mas transmitindo via SSE.
     *
     * Eventos enviados:
     *   event: summary  data: {"delta": "pedaço do texto"}   → texto aparecendo na tela
     *   event: done     data: {"reportId", "summary", "keywords"} → fim, relatório salvo
     *   event: erro     data: {"mensagem": "..."}            → falha tratada
     *
     * O front consome com fetch + ReadableStream (EventSource não faz POST).
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody GenerateRequest request,
                             @AuthenticationPrincipal UUID sessionId) {

        // Sem timeout do lado do Spring: quem limita é o .timeout() do Reactor
        // dentro do NvidiaAiService (nvidia.api.stream-timeout-seconds).
        SseEmitter emitter = new SseEmitter(0L);

        try {
            GenerateService.ContextoGeracao contexto = generateService.preparar(request, sessionId);
            NvidiaAiService.AiStreamResult ai =
                    nvidiaAiService.streamSummary(contexto.cvText(), contexto.jobText());

            Disposable assinatura = ai.deltas().subscribe(

                    delta -> enviar(emitter, "summary", Map.of("delta", delta)),

                    erro -> {
                        log.warn("Streaming da geração falhou: {}", erro.getMessage());
                        enviar(emitter, "erro", Map.of("mensagem", mensagemAmigavel(erro)));
                        emitter.complete();
                    },

                    () -> {
                        try {
                            String bruto = ai.rawBuffer().toString();
                            String summary = PartialSummaryParser.extractSummary(bruto);

                            if (summary.isBlank()) {
                                enviar(emitter, "erro", Map.of("mensagem",
                                        "A IA não devolveu um resumo válido. Tente novamente."));
                                return;
                            }

                            GenerateResponseDto dto = generateService.salvar(
                                    request, sessionId,
                                    summary,
                                    PartialSummaryParser.extractKeywords(bruto));

                            enviar(emitter, "done", Map.of(
                                    "reportId", dto.reportId().toString(),
                                    "summary", dto.summary(),
                                    "keywords", dto.keywords(),
                                    "fromCache", false));

                        } catch (Exception e) {
                            log.error("Falha ao salvar o relatório gerado por streaming", e);
                            enviar(emitter, "erro", Map.of("mensagem",
                                    "Resposta gerada, mas não foi possível salvar. Tente novamente."));
                        } finally {
                            emitter.complete();
                        }
                    });

            // Se o cliente desconectar/cancelar, cancelamos a assinatura do Reactor
            // (sem isso, o stream continuaria consumindo a chamada da IA à toa).
            emitter.onCompletion(assinatura::dispose);
            emitter.onError(e -> assinatura.dispose());
            emitter.onTimeout(assinatura::dispose);

        } catch (NvidiaRateLimitException | SessionRateLimitException
                 | IllegalArgumentException | ResourceNotFoundException e) {
            // Erros de validação/limite ANTES do stream: entrega como evento tratado,
            // preservando a mensagem específica que o front já sabe exibir.
            log.warn("Streaming recusado antes de iniciar: {}", e.getMessage());
            enviar(emitter, "erro", Map.of("mensagem", e.getMessage()));
            emitter.complete();
        } catch (Exception e) {
            log.error("Erro inesperado ao iniciar o streaming", e);
            emitter.completeWithError(e);
        }

        return emitter;
    }

    private void enviar(SseEmitter emitter, String evento, Object dados) {
        try {
            emitter.send(SseEmitter.event()
                    .name(evento)
                    .data(dados, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // Cliente desconectou no meio (fechou a aba, cancelou): não é erro de negócio.
            log.debug("Cliente desconectou durante o stream: {}", e.getMessage());
        }
    }

    private String mensagemAmigavel(Throwable erro) {
        if (erro instanceof NvidiaRateLimitException) {
            return erro.getMessage();
        }
        if (erro instanceof TimeoutException) {
            return "A IA demorou demais para responder. Tente novamente.";
        }
        return "Falha ao gerar a resposta. Tente novamente em instantes.";
    }
}
