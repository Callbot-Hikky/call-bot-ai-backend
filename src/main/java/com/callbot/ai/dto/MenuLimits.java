package com.callbot.ai.dto;

import com.callbot.ai.model.RestaurantMenuFile;

/** Limites d'upload, source unique cote back, renvoyees au front. */
public record MenuLimits(long pdfMaxBytes, int pdfMaxCount, long imageMaxBytes, int imageMaxCount) {

    public static final MenuLimits DEFAULT = new MenuLimits(10L * 1024 * 1024, 5, 5L * 1024 * 1024, 8);

    /** Plafond du genre donne : plusieurs cartes en PDF (plats, vins, desserts) ou plusieurs photos. */
    public int maxCountFor(String kind) {
        return RestaurantMenuFile.KIND_PDF.equals(kind) ? pdfMaxCount : imageMaxCount;
    }
}
