package com.example.server.security;

import com.example.server.service.AuthService;
import com.example.server.service.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedRequestProvidesUserIdToLegacyControllers() throws Exception {
        JwtService jwtService = mock(JwtService.class);
        RestAuthenticationEntryPoint entryPoint = mock(RestAuthenticationEntryPoint.class);
        when(jwtService.parseToken("valid-token"))
                .thenReturn(new AuthenticatedUser(7L, "tester", "USER"));
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, entryPoint);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/analysis/ai");
        request.addHeader("Authorization", "Bearer valid-token");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getAttribute(AuthService.REQUEST_USER_ID)).isEqualTo(7L);
    }
}
