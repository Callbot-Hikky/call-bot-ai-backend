package com.callbot.ai.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.callbot.ai.model.RestaurantMenuFile;

/**
 * Reponse binaire d'un fichier de menu. Le Content-Type est celui detecte a
 * l'upload, jamais celui annonce par le client ; nosniff empeche un navigateur
 * de le reinterpreter ; l'ETag (l'id, le contenu d'un fichier ne change jamais)
 * permet la revalidation sans retelechargement.
 *
 * <p>Ces fichiers sont faits pour etre incorpores par NOS pages (apercu du
 * restaurateur, carte publique) : SecurityConfig assouplit X-Frame-Options en
 * « meme origine » sur ces seules URL, le reste de l'API garde DENY.
 */
final class MenuFileHttp {

    private MenuFileHttp() {
    }

    static ResponseEntity<byte[]> inline(RestaurantMenuFile file, CacheControl cacheControl) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .header("Content-Disposition", "inline")
                .header("X-Content-Type-Options", "nosniff")
                .eTag("\"" + file.getId() + "\"")
                .cacheControl(cacheControl)
                .body(file.getData());
    }
}
