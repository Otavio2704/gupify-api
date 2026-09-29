package com.gupify.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * FIX DE LATÊNCIA — timeout obrigatório no cliente HTTP usado pelo Spring AI.
 *
 * Sem isto, o RestClient que o Spring AI usa para falar com a NVIDIA NIM é criado
 * com a fábrica padrão, cujo read-timeout é ZERO = espera infinita.
 *
 * Sintoma real medido em produção (28/09/2026): POST /api/generate ficou
 * 545 segundos pendurado e só terminou porque a chamada eventualmente voltou.
 * O navegador do usuário desiste antes disso — para ele, a "resposta nunca aparece".
 * Pior: enquanto isso, uma conexão/thread do servidor fica presa nos 3 retries.
 *
 * Com esses timeouts a requisição falha rápido (e com erro tratado) em vez de
 * travar o usuário por minutos.
 *
 * O Spring AI 1.0.0-M6 pega este bean automaticamente (ObjectProvider<RestClient.Builder>).
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);   // 5s para abrir conexão com a NVIDIA
        factory.setReadTimeout(90_000);     // 90s de teto para a geração completa
                                            // (com 2 tentativas, pior caso ~3min)

        return RestClient.builder().requestFactory(factory);
    }
}
