package com.callbot.ai.service;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.AuthResponse;
import com.callbot.ai.dto.LoginRequest;
import com.callbot.ai.dto.RegisterRequest;
import com.callbot.ai.exception.EmailAlreadyUsedException;
import com.callbot.ai.model.Organization;
import com.callbot.ai.model.Role;
import com.callbot.ai.model.User;
import com.callbot.ai.repository.OrganizationRepository;
import com.callbot.ai.repository.UserRepository;
import com.callbot.ai.security.JwtService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyUsedException(request.email());
        }

        // A new sign-up owns a fresh organization; the registrant becomes its owner.
        // The organization name is a placeholder until an onboarding step sets it.
        Organization organization = organizationRepository.save(
                Organization.builder().name(request.email()).build());

        User user = User.builder()
                .organizationId(organization.getId())
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(Role.OWNER)
                .build();
        userRepository.save(user);

        return AuthResponse.bearer(jwtService.generateToken(user.getEmail()));
    }

    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password()));

        return AuthResponse.bearer(jwtService.generateToken(request.email()));
    }
}
