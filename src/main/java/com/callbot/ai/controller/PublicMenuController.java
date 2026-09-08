package com.callbot.ai.controller;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.callbot.ai.dto.PublicMenuResponse;
import com.callbot.ai.model.RestaurantMenuFile;
import com.callbot.ai.service.MenuService;

import lombok.RequiredArgsConstructor;

/**
 * Lecture publique du menu, sans authentification (voir SecurityConfig).
 * Ne renvoie que le menu et le nom du restaurant.
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
        RestaurantMenuFile file = menuService.getFile(restaurantId, fileId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .header("Content-Disposition", "inline")
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
                .body(file.getData());
    }
}
