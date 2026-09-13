package com.callbot.ai.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.callbot.ai.config.ServiceProperties;

import jakarta.servlet.FilterChain;

@ExtendWith(MockitoExtension.class)
class ServiceApiKeyFilterTest {

    @Mock
    private FilterChain chain;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validKey_grantsServiceRole() throws Exception {
        run("secret", "secret");

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_SERVICE");
    }

    @Test
    void wrongKey_leavesRequestAnonymous() throws Exception {
        run("secret", "secret-but-longer");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void missingHeader_leavesRequestAnonymous() throws Exception {
        run("secret", null);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void unconfiguredKey_isInert_evenWhenHeaderIsEmpty() throws Exception {
        run("", "");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void matches_isNullSafeAndRejectsPrefixes() {
        assertThat(ServiceApiKeyFilter.matches("secret", null)).isFalse();
        assertThat(ServiceApiKeyFilter.matches("secret", "secre")).isFalse();
        assertThat(ServiceApiKeyFilter.matches("secret", "secret")).isTrue();
    }

    /** Runs the filter once and checks the chain always continues. */
    private void run(String configured, String provided) throws Exception {
        var filter = new ServiceApiKeyFilter(new ServiceProperties(configured));
        var request = new MockHttpServletRequest();
        if (provided != null) {
            request.addHeader(ServiceApiKeyFilter.API_KEY_HEADER, provided);
        }
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}
