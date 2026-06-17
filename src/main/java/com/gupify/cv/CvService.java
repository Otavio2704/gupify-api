package com.gupify.cv;

import com.gupify.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CvService {

    // Magic bytes de PDF: %PDF → 25 50 44 46
    private static final byte[] PDF_MAGIC = {0x25, 0x50, 0x44, 0x46};

    // Magic bytes de DOCX (ZIP): PK → 50 4B 03 04
    private static final byte[] DOCX_MAGIC = {0x50, 0x4B, 0x03, 0x04};

    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024; // 5MB

    private static final List<String> INJECTION_PATTERNS = List.of(
            "ignore previous", "disregard all", "you are now", "act as",
            "ignore all instructions", "forget everything", "new instructions"
    );

    private final CvRepository cvRepository;

    // =========================================================================
    // Público
    // =========================================================================

    @Transactional
    public CvResponseDto upload(MultipartFile file, UUID sessionId) throws IOException {
        validateFileSize(file);

        String detectedType = detectMimeType(file);
        String rawText = extractText(file, detectedType);
        rawText = sanitizeText(rawText);

        Cv cv = new Cv();
        cv.setSessionId(sessionId);
        cv.setFileName(file.getOriginalFilename());
        cv.setFileType(detectedType);
        cv.setRawText(rawText);

        Cv saved = cvRepository.save(cv);
        log.info("CV salvo com sucesso. id={}, sessionId={}", saved.getId(), sessionId);
        return CvResponseDto.from(saved);
    }

    @Transactional(readOnly = true)
    public List<CvResponseDto> listBySession(UUID sessionId) {
        return cvRepository.findBySessionId(sessionId)
                .stream()
                .map(CvResponseDto::from)
                .toList();
    }

    @Transactional
    public void delete(UUID cvId, UUID sessionId) {
        Cv cv = cvRepository.findByIdAndSessionId(cvId, sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("CV não encontrado"));
        cvRepository.delete(cv);
        log.info("CV removido. id={}, sessionId={}", cvId, sessionId);
    }

    /**
     * Uso interno apenas — nunca exposto via endpoint.
     */
    @Transactional(readOnly = true)
    public String getRawText(UUID cvId, UUID sessionId) {
        return cvRepository.findByIdAndSessionId(cvId, sessionId)
                .map(Cv::getRawText)
                .orElseThrow(() -> {
                    log.warn("Tentativa de acesso a CV de outra sessão. cvId={}, sessionId={}",
                            cvId, sessionId);
                    return new AccessDeniedException("Acesso negado ao CV solicitado");
                });
    }

    // =========================================================================
    // Privado
    // =========================================================================

    private void validateFileSize(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Arquivo não pode ser vazio");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("Arquivo excede o tamanho máximo de 5MB");
        }
    }

    /**
     * Detecta o tipo real do arquivo pelos magic bytes.
     * Não confia no Content-Type informado pelo cliente.
     *
     * @return "PDF" ou "DOCX"
     * @throws IllegalArgumentException se o tipo não for suportado
     */
    private String detectMimeType(MultipartFile file) throws IOException {
        byte[] header = readHeader(file, 4);

        if (startsWith(header, PDF_MAGIC)) {
            log.debug("Tipo detectado: PDF. fileName={}", file.getOriginalFilename());
            return "PDF";
        }

        if (startsWith(header, DOCX_MAGIC)) {
            log.debug("Tipo detectado: DOCX. fileName={}", file.getOriginalFilename());
            return "DOCX";
        }

        log.warn("Tipo de arquivo rejeitado. fileName={}, declaredContentType={}",
                file.getOriginalFilename(), file.getContentType());
        throw new IllegalArgumentException(
                "Tipo de arquivo não suportado. Envie apenas PDF ou DOCX."
        );
    }

    /**
     * Lê os primeiros N bytes do arquivo para verificar magic bytes.
     */
    private byte[] readHeader(MultipartFile file, int length) throws IOException {
        try (InputStream is = file.getInputStream()) {
            byte[] header = new byte[length];
            int bytesRead = is.read(header, 0, length);
            if (bytesRead < length) {
                throw new IllegalArgumentException("Arquivo muito pequeno ou corrompido");
            }
            return header;
        }
    }

    /**
     * Verifica se o array começa com o padrão esperado.
     */
    private boolean startsWith(byte[] data, byte[] pattern) {
        if (data.length < pattern.length) return false;
        for (int i = 0; i < pattern.length; i++) {
            if (data[i] != pattern[i]) return false;
        }
        return true;
    }

    private String extractText(MultipartFile file, String fileType) throws IOException {
        try {
            return switch (fileType) {
                case "PDF" -> extractFromPdf(file);
                case "DOCX" -> extractFromDocx(file);
                default -> throw new IllegalArgumentException("Tipo não suportado: " + fileType);
            };
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Falha ao extrair texto do arquivo: {}", e.getMessage());
            throw new IllegalArgumentException(
                    "Não foi possível extrair o texto do arquivo. Verifique se o arquivo não está corrompido."
            );
        }
    }

    private String extractFromPdf(MultipartFile file) throws IOException {
        byte[] bytes = file.getBytes();
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    private String extractFromDocx(MultipartFile file) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(file.getInputStream());
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        }
    }

    /**
     * Sanitiza o texto removendo padrões comuns de prompt injection.
     */
    private String sanitizeText(String text) {
        String sanitized = text;
        for (String pattern : INJECTION_PATTERNS) {
            if (sanitized.toLowerCase().contains(pattern.toLowerCase())) {
                log.warn("Possível prompt injection detectado no CV. Padrão removido: '{}'", pattern);
                sanitized = sanitized.replaceAll("(?i)" + java.util.regex.Pattern.quote(pattern), "[REMOVIDO]");
            }
        }
        return sanitized;
    }
}