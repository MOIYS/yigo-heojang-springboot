package com.wallet.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.filter.CharacterEncodingFilter;

import com.wallet.auth.filter.JwtAuthenticationFilter;

class WebFilterRegistrationTest {

    private final WebFilterConfiguration configuration = new WebFilterConfiguration();

    @Test
    void encodingFilterKeepsLegacyNameScopeDispatcherAndOrder() {
        CharacterEncodingFilter filter = configuration.encodingFilter();
        FilterRegistrationBean<CharacterEncodingFilter> registration =
            configuration.encodingFilterRegistration(filter);

        assertThat(filter.getEncoding()).isEqualTo("UTF-8");
        assertThat(filter.isForceRequestEncoding()).isTrue();
        assertThat(filter.isForceResponseEncoding()).isTrue();
        assertThat(registration.getFilter()).isSameAs(filter);
        assertThat(registration.getUrlPatterns()).containsExactly("/*");
        assertThat(registration.getOrder()).isEqualTo(WebFilterConfiguration.ENCODING_FILTER_ORDER);
    }

    @Test
    void jwtFilterKeepsLegacyApiScopeDispatcherAndRunsAfterEncoding() {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(null, new ObjectMapper(), null);
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
            configuration.jwtAuthenticationFilterRegistration(filter);

        assertThat(registration.getFilter()).isSameAs(filter);
        assertThat(registration.getUrlPatterns()).containsExactly("/api/*");
        assertThat(registration.getOrder())
            .isEqualTo(WebFilterConfiguration.JWT_FILTER_ORDER)
            .isGreaterThan(WebFilterConfiguration.ENCODING_FILTER_ORDER);
    }
}
