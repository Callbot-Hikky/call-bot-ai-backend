package com.callbot.ai.service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Types de fichiers acceptes pour le menu, reconnus sur les premiers octets
 * (magic bytes) et jamais sur l'extension ni sur le Content-Type annonce par le
 * client : un « .pdf » renomme peut contenir n'importe quoi.
 */
public enum MenuFileType {

    PDF("application/pdf", "pdf"),
    JPEG("image/jpeg", "image"),
    PNG("image/png", "image"),
    WEBP("image/webp", "image");

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] RIFF_MAGIC = "RIFF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WEBP_MAGIC = "WEBP".getBytes(StandardCharsets.US_ASCII);

    private final String contentType;
    private final String kind;

    MenuFileType(String contentType, String kind) {
        this.contentType = contentType;
        this.kind = kind;
    }

    public String contentType() {
        return contentType;
    }

    /** "pdf" ou "image", la valeur stockee dans {@code restaurant_menu_files.kind}. */
    public String kind() {
        return kind;
    }

    public static Optional<MenuFileType> detect(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return Optional.empty();
        }
        if (startsWith(bytes, 0, PDF_MAGIC)) {
            return Optional.of(PDF);
        }
        if (startsWith(bytes, 0, JPEG_MAGIC)) {
            return Optional.of(JPEG);
        }
        if (startsWith(bytes, 0, PNG_MAGIC)) {
            return Optional.of(PNG);
        }
        if (startsWith(bytes, 0, RIFF_MAGIC) && startsWith(bytes, 8, WEBP_MAGIC)) {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] bytes, int offset, byte[] magic) {
        if (bytes.length < offset + magic.length) {
            return false;
        }
        return Arrays.equals(bytes, offset, offset + magic.length, magic, 0, magic.length);
    }
}
