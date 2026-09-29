package com.gupify.generate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Cliente SSE direto para a NVIDIA NIM — usado APENAS no caminho de streaming
 * (o caminho clássico continua no ChatClient do Spring AI).
 *
 * POR QUE ISTO EXISTE (descobriro em produção em 29/09/2026, com medição):
 *
 * 1. O Spring AI 1.0.0-M6 NÃO sabe ler o campo "reasoning_content" dos deltas do
 *    stream (a string não existe em nenhuma classe do jar). Para um modelo de
 *    raciocínio como o DeepSeek V4.1 Flash, o app ficava CEGO enquanto o modelo
 *    "pensava": recebia os pedaços de raciocínio e descartava, vendo silêncio
 *    absoluto. O timeout de 120s disparava mesmo com a NIM transmitindo normal,
 *    e o usuário via "A IA demorou demais para responder".
 *
 * 2. Na API hospedada da NVIDIA, o parâmetro "reasoning_effort" é SILENCIOSAMENTE
 *    IGNORADO nos modelos DeepSeek V4 (bug relatado no fórum de desenvolvimento da
 *    NVIDIA — o campo é aceito sem erro, mas não tem efeito). Ou seja:
 *    "spring.ai.openai.chat.options.reasoning-effort=none" NÃO desliga o thinking.
 *
 * 3. O mecanismo que a NIM realmente respeita é "chat_template_kwargs"
 *    ({"thinking": false} nos DeepSeek/Kimi; {"enable_thinking": false} nos
 *    Qwen/Nemotron/GLM). O Spring AI M6 não tem "extraBody", então não havia como
 *    enviar isso pelo caminho padrão — daí este cliente montar o corpo à mão.
 *
 * O QUE ELE FAZ:
 *  - Monta a requisição (modelo, mensagens, stream, usage e os kwargs de thinking).
 *  - Separa RACIOCÍNIO de CONTEÚDO: o conteúdo alimenta o resumo do usuário; o
 *    raciocínio é contado e logado (diagnóstico honesto do que a NIM está fazendo).
 *  - Se o modelo principal não produzir TEXTO em N segundos, troca sozinho para um
 *    MODELO DE RESERVA sem raciocínio (padrão: google/gemma-4-31b-it). Assim o
 *    usuário vê o texto rápido mesmo se o thinking continuar ligado na NIM.
 *  - Propaga erro HTTP com o CORPO da resposta (ex.: 400 explicando o parâmetro
 *    recusado) em vez de virar timeout silencioso.
 */
@Slf4j
@Component
public class NvidiaNimSseClient {

    /** Tipos de evento que interessam no stream da NIM. */
    public enum Tipo { RACIOCINIO, CONTEUDO, USO }

    /**
     * @param tipo              RACIOCINIO (thinking), CONTEUDO (texto do usuário) ou USO (tokens)
     * @param texto             pedaço de texto (vazio quando o tipo é USO)
     * @param tokensRaciocinio  tokens de raciocínio do chunk de usage (pode ser null)
     * @param tokensConteudo    tokens de conteúdo do chunk de usage (pode ser null)
     */
    public record Evento(Tipo tipo, String texto, Integer tokensRaciocinio, Integer tokensConteudo) {
        public static Evento raciocinio(String texto) {
            return new Evento(Tipo.RACIOCINIO, texto, null, null);
        }

        public static Evento conteudo(String texto) {
            return new Evento(Tipo.CONTEUDO, texto, null, null);
        }

        public static Evento uso(Integer tokensRaciocinio, Integer tokensConteudo) {
            return new Evento(Tipo.USO, "", tokensRaciocinio, tokensConteudo);
        }
    }

    /** Erro de comunicação com a NIM que já traz uma mensagem pronta para o log. */
    public static class NimException extends RuntimeException {
        public NimException(String mensagem) {
            super(mensagem);
        }
    }

    private static final int MAX_TOKENS_RESPOSTA = 4096;
    private static final Duration TIMEOUT_REQUISICAO = Duration.ofSeconds(120);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // HTTP/1.1 fixo: o SSE sobre HTTP/2 do HttpClient do JDK é um risco
            // desnecessário contra a NIM (stream que morre sem erro visível deixaria
            // o app em silêncio até o timeout, exatamente o sintoma que perseguimos).
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ScheduledExecutorService agendador = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "nim-troca-modelo");
        t.setDaemon(true);
        return t;
    });

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    /** Envia chat_template_kwargs para desligar o thinking (padrão: sim). */
    @Value("${nvidia.api.desligar-thinking:true}")
    private boolean desligarThinking;

    /** Segundos sem TEXTO antes de trocar para o modelo de reserva (0 desliga a troca). */
    @Value("${nvidia.api.reserva-apos-segundos:15}")
    private long reservaAposSegundos;

    /** Modelo de reserva; use um modelo SEM raciocínio. Vazio desliga a troca. */
    @Value("${nvidia.api.modelo-reserva:}")
    private String modeloReserva;

    /**
     * Abre o stream da NIM para o modelo principal e, se preciso, para os reservas.
     * O fluxo resultante emite eventos até o fim da resposta (ou até um erro real).
     *
     * A reserva aceita uma LISTA separada por vírgula: se um modelo ficar mudo, o
     * seguinte assume — assim uma fila cheia na NVIDIA não deixa o usuário sem resposta.
     */
    public Flux<Evento> stream(String modeloPrincipal, String systemPrompt, String userPrompt) {
        List<String> modelos = new ArrayList<>();
        modelos.add(modeloPrincipal);
        if (modeloReserva != null) {
            for (String candidato : modeloReserva.split(",")) {
                String limpo = candidato.trim();
                if (!limpo.isEmpty() && !modelos.contains(limpo)) {
                    modelos.add(limpo);
                }
            }
        }

        log.info("NIM: cadeia de modelos para esta geração = {}", modelos);

        return Flux.create(sink -> {
            AtomicReference<Stream<String>> streamAtual = new AtomicReference<>();
            AtomicBoolean cancelado = new AtomicBoolean(false);

            // Cliente desconectou (fechou a aba, cancelou o fetch): encerra a leitura.
            sink.onDispose(() -> {
                cancelado.set(true);
                fechar(streamAtual);
            });

            Thread worker = new Thread(
                    () -> bombear(sink, modelos, systemPrompt, userPrompt, streamAtual, cancelado),
                    "nim-stream");
            worker.setDaemon(true);
            worker.start();
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    // =====================================================================
    // BOMBEAMENTO — roda numa thread própria porque a leitura do SSE é bloqueante
    // =====================================================================
    private void bombear(FluxSink<Evento> sink, List<String> modelos, String systemPrompt,
                         String userPrompt, AtomicReference<Stream<String>> streamAtual,
                         AtomicBoolean cancelado) {

        for (int i = 0; i < modelos.size(); i++) {
            if (cancelado.get()) {
                return;
            }

            String modelo = modelos.get(i);
            boolean temReserva = i < modelos.size() - 1;
            String proximo = temReserva ? modelos.get(i + 1) : null;

            AtomicBoolean jaTemTexto = new AtomicBoolean(false);
            AtomicBoolean trocar = new AtomicBoolean(false);

            // Alarme: se o modelo não mandar TEXTO em N segundos, troca de modelo.
            // (O raciocínio NÃO conta como texto: é justamente o que queremos evitar.)
            ScheduledFuture<?> alarme = null;
            if (temReserva && reservaAposSegundos > 0) {
                alarme = agendador.schedule(() -> {
                    if (cancelado.get() || jaTemTexto.get()) {
                        return;
                    }
                    log.warn("NIM: modelo {} não produziu TEXTO em {}s — trocando para o reserva {}",
                            modelo, reservaAposSegundos, proximo);
                    trocar.set(true);
                    fechar(streamAtual);
                }, reservaAposSegundos, TimeUnit.SECONDS);
            }

            long inicio = System.currentTimeMillis();
            boolean encerrouSemTexto = false;

            // Limite para a RESPOSTA INICIAL (cabeçalhos HTTP). Existe um cenário em que
            // a NIM aceita a conexão e fica muda (modelo pensando sem transmitir nada, ou
            // fila do gateway): sem este limite, a thread de leitura ficaria bloqueada e
            // nem a troca de modelo aconteceria — silêncio até o timeout de 120s.
            long limiteRespostaInicial = (temReserva && reservaAposSegundos > 0)
                    ? Math.max(3, reservaAposSegundos - 2)
                    : TIMEOUT_REQUISICAO.toSeconds();

            try {
                log.info("NIM: pedindo stream ao modelo {} (thinking desligado={}, resposta inicial em até {}s)",
                        modelo, desligarThinking, limiteRespostaInicial);

                CompletableFuture<HttpResponse<Stream<String>>> futuro = http.sendAsync(
                        montarRequisicao(modelo, systemPrompt, userPrompt),
                        HttpResponse.BodyHandlers.ofLines());

                HttpResponse<Stream<String>> resposta;
                try {
                    resposta = futuro.get(limiteRespostaInicial, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    futuro.cancel(true);
                    log.warn("NIM: modelo {} não enviou nem os cabeçalhos em {}s — trocando para o reserva {}",
                            modelo, limiteRespostaInicial, proximo);
                    if (temReserva) {
                        continue;
                    }
                    sink.error(new NimException("A NIM não respondeu em " + limiteRespostaInicial + "s."));
                    return;
                }

                if (resposta.statusCode() != 200) {
                    String corpo = resposta.body().limit(30).collect(Collectors.joining("\n"));
                    log.error("NIM: HTTP {} no modelo {} — corpo: {}",
                            resposta.statusCode(), modelo, resumir(corpo));
                    if (temReserva) {
                        continue;
                    }
                    sink.error(new NimException("NIM respondeu HTTP " + resposta.statusCode()
                            + ": " + resumir(corpo)));
                    return;
                }

                log.info("NIM: stream aberto (modelo={}) em {} ms", modelo, System.currentTimeMillis() - inicio);

                Stream<String> linhas = resposta.body();
                streamAtual.set(linhas);

                Iterator<String> iterador = linhas.iterator();
                while (iterador.hasNext() && !cancelado.get() && !trocar.get()) {
                    String linha = iterador.next();
                    if (linha.startsWith("data:")) {
                        String dado = linha.substring(5).trim();
                        if ("[DONE]".equals(dado)) {
                            break;
                        }
                        processar(dado, sink, jaTemTexto);
                    }
                }

                encerrouSemTexto = !jaTemTexto.get();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (cancelado.get()) {
                    return;
                }
                if (!trocar.get()) {
                    log.error("NIM: falha lendo o stream do modelo {}: {}", modelo, e.toString());
                }
                if (!temReserva) {
                    sink.error(e);
                    return;
                }
                continue;
            } finally {
                if (alarme != null) {
                    alarme.cancel(false);
                }
                streamAtual.set(null);
            }

            if (cancelado.get()) {
                return;
            }
            if (trocar.get()) {
                continue;
            }
            if (jaTemTexto.get()) {
                sink.complete();
                return;
            }
            if (!temReserva) {
                sink.error(new NimException("A NIM encerrou a resposta sem enviar texto."));
                return;
            }
            if (encerrouSemTexto) {
                log.warn("NIM: modelo {} encerrou sem texto — tentando o reserva {}", modelo, proximo);
            }
        }

        // Chegou aqui = nenhum modelo entregou texto. Melhor um erro claro do que um
        // stream que termina em silêncio (a tela ficava "carregando" para sempre).
        sink.error(new NimException("Nenhum modelo da NIM produziu texto."));
    }

    /** Lê um chunk JSON do SSE e emite o evento correspondente. */
    private void processar(String json, FluxSink<Evento> sink, AtomicBoolean jaTemTexto) {
        try {
            JsonNode raiz = mapper.readTree(json);

            JsonNode usage = raiz.get("usage");
            if (usage != null && !usage.isNull()) {
                JsonNode detalhes = usage.get("completion_tokens_details");
                Integer tokensRaciocinio = (detalhes != null && detalhes.hasNonNull("reasoning_tokens"))
                        ? detalhes.get("reasoning_tokens").asInt()
                        : null;
                Integer tokensConteudo = usage.hasNonNull("completion_tokens")
                        ? usage.get("completion_tokens").asInt()
                        : null;
                if (tokensRaciocinio != null || tokensConteudo != null) {
                    sink.next(Evento.uso(tokensRaciocinio, tokensConteudo));
                }
            }

            JsonNode escolhas = raiz.get("choices");
            if (escolhas == null || !escolhas.isArray() || escolhas.isEmpty()) {
                return;
            }

            JsonNode delta = escolhas.get(0).get("delta");
            if (delta == null || delta.isNull()) {
                return;
            }

            JsonNode raciocinio = delta.get("reasoning_content");
            if (raciocinio != null && !raciocinio.isNull()) {
                String texto = raciocinio.asText();
                if (!texto.isEmpty()) {
                    sink.next(Evento.raciocinio(texto));
                }
            }

            JsonNode conteudo = delta.get("content");
            if (conteudo != null && !conteudo.isNull()) {
                String texto = conteudo.asText();
                if (!texto.isEmpty()) {
                    jaTemTexto.set(true);
                    sink.next(Evento.conteudo(texto));
                }
            }
        } catch (Exception e) {
            log.debug("NIM: linha SSE ignorada ({}): {}", e.toString(), json);
        }
    }

    private HttpRequest montarRequisicao(String modelo, String systemPrompt, String userPrompt)
            throws Exception {
        ObjectNode corpo = mapper.createObjectNode();
        corpo.put("model", modelo);

        ArrayNode mensagens = corpo.putArray("messages");
        mensagens.addObject().put("role", "system").put("content", systemPrompt);
        mensagens.addObject().put("role", "user").put("content", userPrompt);

        corpo.put("stream", true);
        corpo.putObject("stream_options").put("include_usage", true);
        corpo.put("max_tokens", MAX_TOKENS_RESPOSTA);

        if (desligarThinking) {
            // O canal que a NVIDIA NIM realmente respeita para desligar o thinking.
            // Mandamos as duas convenções: cada família de modelo lê a sua chave e
            // ignora a outra (DeepSeek/Kimi usam "thinking"; Qwen/Nemotron/GLM usam
            // "enable_thinking").
            ObjectNode kwargs = corpo.putObject("chat_template_kwargs");
            kwargs.put("thinking", false);
            kwargs.put("enable_thinking", false);
        }

        return HttpRequest.newBuilder(endpoint())
                .timeout(TIMEOUT_REQUISICAO)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(corpo), StandardCharsets.UTF_8))
                .build();
    }

    private URI endpoint() {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String caminho = base.endsWith("/v1") ? "/chat/completions" : "/v1/chat/completions";
        return URI.create(base + caminho);
    }

    /** Fecha o stream aberto (o que desbloqueia a thread de leitura). */
    private void fechar(AtomicReference<Stream<String>> streamAtual) {
        Stream<String> s = streamAtual.getAndSet(null);
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
                // fechar duas vezes / conexão já encerrada: não é problema.
            }
        }
    }

    private String resumir(String texto) {
        if (texto == null) {
            return "";
        }
        String limpo = texto.replaceAll("\\s+", " ").trim();
        return limpo.length() > 300 ? limpo.substring(0, 300) + "..." : limpo;
    }
}
