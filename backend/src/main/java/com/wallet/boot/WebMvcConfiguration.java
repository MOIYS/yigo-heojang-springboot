package com.wallet.boot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Controller;
import org.springframework.validation.Validator;
import org.springframework.validation.beanvalidation.OptionalValidatorFactoryBean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Boot MVC 기본 설정으로 API 응답 표현이 달라지지 않도록 명시적으로 구성하는 MVC 설정. */
@Configuration
@ComponentScan(basePackages = "com.wallet", useDefaultFilters = false,
    includeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = Controller.class))
public class WebMvcConfiguration implements WebMvcConfigurer {
    private final String allowedOrigin;

    public WebMvcConfiguration(
        @Value("${app.cors.allowed-origin:http://localhost:5173}") String allowedOrigin
    ) {
        this.allowedOrigin = allowedOrigin;
    }

    // 구체적인 Jakarta Validator 타입을 선언해 Boot가 별도 검증기를 추가하지 않도록 한다.
    @Bean("org.springframework.validation.beanvalidation.OptionalValidatorFactoryBean#0")
    @Primary
    public OptionalValidatorFactoryBean mvcBeanValidator() {
        return new OptionalValidatorFactoryBean();
    }

    @Override
    public Validator getValidator() {
        return mvcBeanValidator();
    }

    @Bean
    public Map<String, CorsConfiguration> mvcCorsConfigurations() {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(allowedOrigin));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("*"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(1800L);
        var configurations = new LinkedHashMap<String, CorsConfiguration>();
        configurations.put("/api/**", cors);
        return configurations;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        mvcCorsConfigurations().forEach((path, cors) -> registry.addMapping(path).combine(cors));
    }
}
