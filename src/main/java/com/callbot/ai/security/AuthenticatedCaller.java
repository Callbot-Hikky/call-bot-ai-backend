package com.callbot.ai.security;

import org.springframework.security.core.Authentication;

/**
 * Reads who is calling, at the web layer.
 *
 * <p>Two kinds of caller reach the API and they must not be treated alike: a dashboard
 * user, whose data is scoped to their organization, and the AI microservice, which
 * authenticates with an API key and legitimately acts across restaurants.
 */
public final class AuthenticatedCaller {

    private static final String SERVICE_ROLE = "ROLE_SERVICE";

    /**
     * Email of the authenticated dashboard user, or {@code null} for the AI
     * microservice — which is not a user and is bound to no organization.
     */
    public static String emailOf(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        boolean isService = authentication.getAuthorities().stream()
                .anyMatch(authority -> SERVICE_ROLE.equals(authority.getAuthority()));
        return isService ? null : authentication.getName();
    }

    private AuthenticatedCaller() {
    }
}
