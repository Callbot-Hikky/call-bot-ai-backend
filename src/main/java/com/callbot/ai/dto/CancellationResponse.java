package com.callbot.ai.dto;

/**
 * Outcome of a diner cancelling their own reservation.
 *
 * <p>{@code refunded} is the answer to the only question they have, and the page says
 * it plainly rather than leaving them to work it out from the window.
 */
public record CancellationResponse(boolean cancelled, boolean refunded, Integer refundedAmountCents) {
}
