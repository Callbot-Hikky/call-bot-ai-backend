package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

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

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private JwtService jwtService;

    @InjectMocks
    private AuthService authService;

    @Test
    void register_persistsHashedOwnerWithOrganizationAndReturnsToken() {
        RegisterRequest request = new RegisterRequest("bob@example.com", "password123");
        UUID organizationId = UUID.randomUUID();
        when(userRepository.existsByEmail("bob@example.com")).thenReturn(false);
        when(organizationRepository.save(any())).thenAnswer(invocation -> {
            Organization organization = invocation.getArgument(0);
            organization.setId(organizationId);
            return organization;
        });
        when(passwordEncoder.encode("password123")).thenReturn("hashed-password");
        when(jwtService.generateToken("bob@example.com")).thenReturn("token-123");

        AuthResponse response = authService.register(request);

        assertThat(response.accessToken()).isEqualTo("token-123");
        assertThat(response.tokenType()).isEqualTo("Bearer");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("bob@example.com");
        assertThat(saved.getPasswordHash()).isEqualTo("hashed-password");
        assertThat(saved.getRole()).isEqualTo(Role.OWNER);
        assertThat(saved.getOrganizationId()).isEqualTo(organizationId);
    }

    @Test
    void register_whenEmailAlreadyUsed_throwsAndDoesNotSave() {
        when(userRepository.existsByEmail("bob@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("bob@example.com", "password123")))
                .isInstanceOf(EmailAlreadyUsedException.class);

        verify(userRepository, never()).save(any());
        verify(organizationRepository, never()).save(any());
    }

    @Test
    void login_authenticatesAndReturnsToken() {
        when(jwtService.generateToken("bob@example.com")).thenReturn("token-123");

        AuthResponse response = authService.login(new LoginRequest("bob@example.com", "password123"));

        assertThat(response.accessToken()).isEqualTo("token-123");
        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
    }

    @Test
    void login_whenAuthenticationFails_propagatesException() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("bad credentials"));

        assertThatThrownBy(() -> authService.login(new LoginRequest("bob@example.com", "wrong")))
                .isInstanceOf(BadCredentialsException.class);
        verify(jwtService, never()).generateToken(any());
    }
}
