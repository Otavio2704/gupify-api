package com.gupify.generate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê um JSON PARCIAL (que ainda está sendo transmitido token a token) e extrai o
 * valor de "summary" já decodificado.
 *
 * Por que isso existe: o modelo devolve a resposta como JSON
 * ({"summary": "...", "keywords": [...]}). Enquanto o texto está chegando, o JSON
 * ainda está inválido/incompleto — não dá para usar um parser normal (Jackson).
 * Este parser "tolerante" extrai o que já existe do campo "summary" para mostrar
 * o texto na tela enquanto a IA escreve, em vez de esperar a resposta inteira.
 *
 * Regras:
 *  - antes de ver a chave "summary", devolve "" (nada a mostrar ainda);
 *  - se a string estiver cortada ao meio (ex.: escape "\" no fim do chunk),
 *    devolve o que já foi decodificado e ignora o resto até o próximo chunk;
 *  - nunca lança exceção: qualquer formato inesperado apenas devolve o que deu.
 */
final class PartialSummaryParser {

    private static final Pattern SUMMARY_KEY = Pattern.compile("\"summary\"\\s*:\\s*\"");
    private static final Pattern KEYWORDS_BLOCO =
            Pattern.compile("\"keywords\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern STRING_ITEM =
            Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private PartialSummaryParser() {
    }

    /** Texto de "summary" já decodificado do que chegou até agora (ou "" se ainda não há). */
    static String extractSummary(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }

        Matcher m = SUMMARY_KEY.matcher(raw);
        if (!m.find()) {
            return "";
        }

        StringBuilder out = new StringBuilder();
        int i = m.end();

        while (i < raw.length()) {
            char c = raw.charAt(i);

            if (c == '"') {
                return out.toString();                      // fim do valor: resumo completo
            }
            if (c != '\\') {
                out.append(c);
                i++;
                continue;
            }

            // daqui pra baixo: sequência de escape
            if (i + 1 >= raw.length()) {
                return out.toString();                      // escape cortado no meio do chunk
            }

            char esc = raw.charAt(i + 1);
            switch (esc) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'u' -> {
                    if (i + 5 >= raw.length()) {
                        return out.toString();              // uXXXX incompleto
                    }
                    try {
                        out.append((char) Integer.parseInt(raw.substring(i + 2, i + 6), 16));
                    } catch (NumberFormatException e) {
                        // uXXXX inválido: ignora e segue
                    }
                    i += 4;
                }
                default -> out.append(esc);
            }
            i += 2;
        }

        return out.toString();
    }

    /**
     * Lista de keywords do JSON final. Serve para o path de streaming, que monta
     * o relatório a partir do texto bruto em vez de desserializar com Jackson.
     */
    static List<String> extractKeywords(String raw) {
        List<String> keywords = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return keywords;
        }

        Matcher bloco = KEYWORDS_BLOCO.matcher(raw);
        if (!bloco.find()) {
            return keywords;
        }

        Matcher item = STRING_ITEM.matcher(bloco.group(1));
        while (item.find()) {
            String valor = item.group(1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .trim();
            if (!valor.isEmpty() && !keywords.contains(valor)) {
                keywords.add(valor);
            }
        }

        return keywords.size() > 3 ? new ArrayList<>(keywords.subList(0, 3)) : keywords;
    }
}
