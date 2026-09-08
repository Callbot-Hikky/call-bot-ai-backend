package com.callbot.ai.controller;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.callbot.ai.dto.MenuFileOrderRequest;
import com.callbot.ai.dto.MenuRequest;
import com.callbot.ai.dto.MenuResponse;
import com.callbot.ai.security.RestaurantAccess;
import com.callbot.ai.service.MenuService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Gestion du menu par le restaurateur. Chaque appel verifie la propriete du restaurant. */
@RestController
@RequestMapping("/api/restaurants/{restaurantId}/menu")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;
    private final RestaurantAccess restaurantAccess;

    @GetMapping
    public MenuResponse get(@PathVariable UUID restaurantId, Authentication authentication) {
        restaurantAccess.requireOwned(restaurantId, authentication);
        return menuService.get(restaurantId);
    }

    @PutMapping
    public MenuResponse upsert(@PathVariable UUID restaurantId,
            @Valid @RequestBody MenuRequest request, Authentication authentication) {
        restaurantAccess.requireOwned(restaurantId, authentication);
        return menuService.upsert(restaurantId, request);
    }

    @PostMapping("/files")
    public MenuResponse upload(@PathVariable UUID restaurantId,
            @RequestPart("file") MultipartFile file, Authentication authentication) {
        restaurantAccess.requireOwned(restaurantId, authentication);
        try {
            return menuService.upload(restaurantId, file.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read uploaded file", e);
        }
    }

    @DeleteMapping("/files/{fileId}")
    public MenuResponse deleteFile(@PathVariable UUID restaurantId, @PathVariable UUID fileId,
            Authentication authentication) {
        restaurantAccess.requireOwned(restaurantId, authentication);
        return menuService.deleteFile(restaurantId, fileId);
    }

    @PutMapping("/files/order")
    public MenuResponse reorder(@PathVariable UUID restaurantId,
            @Valid @RequestBody MenuFileOrderRequest request, Authentication authentication) {
        restaurantAccess.requireOwned(restaurantId, authentication);
        return menuService.reorder(restaurantId, request);
    }
}
