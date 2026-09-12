package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class MenuFileTypeTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Test
    void detect_pdf() {
        byte[] pdf = "%PDF-1.7 contenu".getBytes(StandardCharsets.US_ASCII);
        assertThat(MenuFileType.detect(pdf)).contains(MenuFileType.PDF);
        assertThat(MenuFileType.PDF.contentType()).isEqualTo("application/pdf");
        assertThat(MenuFileType.PDF.kind()).isEqualTo("pdf");
    }

    @Test
    void detect_jpeg() {
        assertThat(MenuFileType.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01)))
                .contains(MenuFileType.JPEG);
        assertThat(MenuFileType.JPEG.kind()).isEqualTo("image");
    }

    @Test
    void detect_png() {
        assertThat(MenuFileType.detect(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D)))
                .contains(MenuFileType.PNG);
    }

    @Test
    void detect_webp() {
        byte[] webp = new byte[16];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, webp, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, webp, 8, 4);
        assertThat(MenuFileType.detect(webp)).contains(MenuFileType.WEBP);
        assertThat(MenuFileType.WEBP.contentType()).isEqualTo("image/webp");
    }

    @Test
    void detect_rejectsSvgHtmlAndUnknown() {
        assertThat(MenuFileType.detect("<svg xmlns=".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(MenuFileType.detect("<!DOCTYPE html>".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(MenuFileType.detect(new byte[0])).isEmpty();
        assertThat(MenuFileType.detect(bytes(0x00, 0x01, 0x02))).isEmpty();
    }

    @Test
    void detect_doesNotTrustAClaimedExtension() {
        // Un « .pdf » qui contient du HTML n'est pas un PDF.
        Optional<MenuFileType> detected = MenuFileType.detect("<html>".getBytes(StandardCharsets.US_ASCII));
        assertThat(detected).isEmpty();
    }
}
