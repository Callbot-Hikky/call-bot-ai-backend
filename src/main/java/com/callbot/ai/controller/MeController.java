package com.callbot.ai.controller;

import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.User;
import com.callbot.ai.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MeController {

    private final UserRepository userRepository;

    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException("User", authentication.getName()));
        return Map.of(
                "id", user.getId(),
                "email", user.getEmail(),
                "organizationId", user.getOrganizationId(),
                "role", user.getRole());
    }
}
