package com.callbot.ai.dto;

/** Limites d'upload, source unique cote back, renvoyees au front. */
public record MenuLimits(long pdfMaxBytes, long imageMaxBytes, int imageMaxCount) {

    public static final MenuLimits DEFAULT = new MenuLimits(10L * 1024 * 1024, 5L * 1024 * 1024, 8);
}
