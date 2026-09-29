package com.gupify.config;

import com.gupify.generate.NvidiaAiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-teste de sanidade do parser de streaming.
 *
 * Este projeto tem um histórico de CI quebrado (os testes não compilam desde
 * junho), então em vez de confiar em `mvn test` este teste roda no boot da
 * aplicação e aparece direto no log do Render:
 *
 *   "Parser de streaming OK (7 casos)."   → tudo certo
 *   "PARSER DE STREAMING COM FALHA: ..."  → aviso explícito, geração por stream quebrada
 *
 * Custo: microssegundos no startup.
 */
@Slf4j
@Configuration
public class StartupSanityCheck {

    @Bean
    public ApplicationRunner parserSanityCheck() {
        return args -> {
            String falha = NvidiaAiService.parserSanidadeFalha();
            if (falha == null) {
                log.info("Parser de streaming OK (7 casos).");
            } else {
                log.error("PARSER DE STREAMING COM FALHA: {}. O endpoint /api/generate/stream "
                        + "vai devolver texto errado — use /api/generate até corrigir.", falha);
            }
        };
    }
}
