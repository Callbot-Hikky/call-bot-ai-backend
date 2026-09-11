package com.callbot.ai.controller;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.callbot.ai.dto.PublicMenuResponse;
import com.callbot.ai.service.MenuService;

import lombok.RequiredArgsConstructor;

/**
 * Lecture publique du menu, sans authentification (voir SecurityConfig).
 * Ne renvoie que le menu et le nom du restaurant, et seulement les fichiers du mode publie.
 */
@RestController
@RequestMapping("/api/public/restaurants/{restaurantId}/menu")
@RequiredArgsConstructor
public class PublicMenuController {

    private final MenuService menuService;

    @GetMapping
    public PublicMenuResponse get(@PathVariable UUID restaurantId) {
        return menuService.getPublic(restaurantId);
    }

    @GetMapping("/files/{fileId}")
    public ResponseEntity<byte[]> file(@PathVariable UUID restaurantId, @PathVariable UUID fileId) {
        return MenuFileHttp.inline(menuService.getPublicFile(restaurantId, fileId),
                // Pas de cache sans revalidation : depublier doit rendre le fichier
                // inaccessible tout de suite, l'ETag suffit pour repondre 304.
                CacheControl.noCache());
    }
}
