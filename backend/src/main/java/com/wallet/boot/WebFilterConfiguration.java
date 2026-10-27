package com.wallet.boot;

import java.util.EnumSet;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.CharacterEncodingFilter;

import com.wallet.auth.filter.JwtAuthenticationFilter;

/** 기존 필터 이름, 순서와 URL 매핑을 유지하는 서블릿 필터 등록. */
@Configuration(proxyBeanMethods = false)
public class WebFilterConfiguration {

    static final int ENCODING_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE;
    static final int JWT_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    @Bean("encodingFilter")
    CharacterEncodingFilter encodingFilter() {
        CharacterEncodingFilter filter = new CharacterEncodingFilter();
        filter.setEncoding("UTF-8");
        filter.setForceRequestEncoding(true);
        filter.setForceResponseEncoding(true);
        return filter;
    }

    @Bean
    FilterRegistrationBean<CharacterEncodingFilter> encodingFilterRegistration(
        @Qualifier("encodingFilter") CharacterEncodingFilter encodingFilter
    ) {
        FilterRegistrationBean<CharacterEncodingFilter> registration =
            new FilterRegistrationBean<>(encodingFilter);
        registration.setName("encodingFilter");
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        registration.setOrder(ENCODING_FILTER_ORDER);
        return registration;
    }

    @Bean
    FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
        JwtAuthenticationFilter jwtAuthenticationFilter
    ) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
            new FilterRegistrationBean<>(jwtAuthenticationFilter);
        registration.setName("jwtAuthenticationFilter");
        registration.addUrlPatterns("/api/*");
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        registration.setOrder(JWT_FILTER_ORDER);
        return registration;
    }

}
