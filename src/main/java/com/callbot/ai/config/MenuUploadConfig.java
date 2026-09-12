package com.callbot.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.callbot.ai.dto.MenuLimits;

import jakarta.servlet.MultipartConfigElement;

/**
 * Limites d'upload derivees de {@link MenuLimits}, la source unique : le
 * conteneur rejette (413) tout fichier plus gros que le plus gros fichier
 * accepte, avant meme d'atteindre un controleur. La limite par type est
 * ensuite verifiee par MenuService.
 */
@Configuration
public class MenuUploadConfig {

    private static final long REQUEST_OVERHEAD_BYTES = 2L * 1024 * 1024;

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        long maxFile = MenuLimits.DEFAULT.pdfMaxBytes();
        return new MultipartConfigElement("", maxFile, maxFile + REQUEST_OVERHEAD_BYTES, 0);
    }
}
