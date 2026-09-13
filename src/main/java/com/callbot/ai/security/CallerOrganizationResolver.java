package com.callbot.ai.security;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import com.callbot.ai.model.User;
import com.callbot.ai.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Resolves the organization a caller is bound to, so services can scope their
 * data to it.
 *
 * <p>Two kinds of caller reach the API. Dashboard users authenticate with a JWT
 * and belong to exactly one organization: they must never see another one's data.
 * The AI microservice authenticates with a global API key, is not a user, and is
 * not bound to any organization — it legitimately acts across restaurants, so it
 * is left unrestricted.
 */
@Component
@RequiredArgsConstructor
public class CallerOrganizationResolver {

    private final UserRepository userRepository;

    /**
     * @param callerEmail the authenticated user's email, or {@code null} for the
     *                    trusted AI microservice (API key authentication)
     * @return the caller's organization, or empty when the caller is not bound to one
     */
    public Optional<UUID> resolve(String callerEmail) {
        return user(callerEmail).map(User::getOrganizationId);
    }

    /**
     * The caller's user id, for actions that must be attributable to a person — waiving
     * a guarantee, or recording a no-show. Empty for the AI microservice, which is not
     * a user and may not take such actions.
     */
    public Optional<UUID> resolveUserId(String callerEmail) {
        return user(callerEmail).map(User::getId);
    }

    private Optional<User> user(String callerEmail) {
        if (callerEmail == null) {
            return Optional.empty();
        }
        return Optional.of(userRepository.findByEmail(callerEmail)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + callerEmail)));
    }
}
