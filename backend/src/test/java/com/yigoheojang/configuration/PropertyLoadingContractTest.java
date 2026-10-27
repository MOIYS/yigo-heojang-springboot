package com.yigoheojang.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import com.wallet.auth.jwt.JwtTokenProvider;
import com.wallet.boot.RootConfiguration;

class PropertyLoadingContractTest {
    @Test
    void orderedFilesAndAllWildcardJwtResourcesAreLoaded() throws Exception {
        try (var context = context(new MockEnvironment(), true, Probe.class)) {
            context.refresh();
            var probe = context.getBean(Probe.class);
            assertThat(probe.layer).isEqualTo("jwt-second");
            assertThat(probe.dbOnly).isEqualTo("db");
            assertThat(probe.redisOnly).isEqualTo("redis");
            assertThat(probe.jwtFirstOnly).isEqualTo("jwt-first");
            assertThat(probe.origin).isEqualTo("http://localhost:5173");
            assertThat(context.getBeansOfType(PropertySourcesPlaceholderConfigurer.class)).hasSize(1);
        }
    }

    @Test
    void externalPropertiesAndEnvironmentSecretsStayAheadOfClasspathFiles() throws Exception {
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment",
            Map.of("JWT_ACCESS_TOKEN_SECRET", "environment-access-secret-at-least-32-bytes",
                "APP_CORS_ALLOWED_ORIGIN", "https://frontend.example.invalid")));
        try (var context = context(environment, true, Probe.class, JwtTokenProvider.class)) {
            context.refresh();
            assertThat(context.getBean(Probe.class).origin).isEqualTo("https://frontend.example.invalid");
            assertThat(context.getBean(Probe.class).secret).isEqualTo("environment-access-secret-at-least-32-bytes");
            assertThat(context.getBean(JwtTokenProvider.class)).isNotNull();
        }
        environment.getPropertySources().addFirst(new MapPropertySource("systemProperties",
            Map.of("JWT_ACCESS_TOKEN_SECRET", "system-property-secret-at-least-32-bytes")));
        try (var context = context(environment, true, Probe.class)) {
            context.refresh();
            assertThat(context.getBean(Probe.class).secret).isEqualTo("system-property-secret-at-least-32-bytes");
        }
        environment.getPropertySources().addFirst(new MapPropertySource("commandLineArgs",
            Map.of("JWT_ACCESS_TOKEN_SECRET", "command-line-secret-at-least-32-bytes")));
        try (var context = context(environment, true, Probe.class)) {
            context.refresh();
            assertThat(context.getBean(Probe.class).secret).isEqualTo("command-line-secret-at-least-32-bytes");
        }
    }

    @Test
    void absentWildcardJwtFileRequiresBothExternalSecrets() throws Exception {
        for (String missing : List.of("JWT_ACCESS_TOKEN_SECRET", "JWT_REFRESH_TOKEN_SECRET")) {
            var environment = new MockEnvironment();
            String other = missing.equals("JWT_ACCESS_TOKEN_SECRET")
                ? "JWT_REFRESH_TOKEN_SECRET" : "JWT_ACCESS_TOKEN_SECRET";
            environment.withProperty(other, "external-test-secret-at-least-32-bytes");
            try (var context = context(environment, false, JwtTokenProvider.class)) {
                assertThatThrownBy(context::refresh).hasStackTraceContaining(missing);
            }
        }
        try (var context = context(secrets(), false, JwtTokenProvider.class)) {
            context.refresh();
            assertThat(context.getBean(JwtTokenProvider.class)).isNotNull();
        }
    }

    @Test
    void blankAndShortExternalSecretsStillFailStartup() throws Exception {
        for (String key : List.of("JWT_ACCESS_TOKEN_SECRET", "JWT_REFRESH_TOKEN_SECRET")) {
            for (String invalid : List.of("", "too-short")) {
                var environment = secrets().withProperty(key, invalid);
                try (var context = context(environment, false, JwtTokenProvider.class)) {
                    assertThatThrownBy(context::refresh).hasStackTraceContaining(key + " must");
                }
            }
        }
    }

    private MockEnvironment secrets() {
        return new MockEnvironment()
            .withProperty("JWT_ACCESS_TOKEN_SECRET", "external-access-secret-at-least-32-bytes")
            .withProperty("JWT_REFRESH_TOKEN_SECRET", "external-refresh-secret-at-least-32-bytes");
    }

    private AnnotationConfigApplicationContext context(MockEnvironment environment, boolean includeJwt,
        Class<?>... beans) throws IOException {
        // 실제 시크릿이 없는 격리된 클래스패스 픽스처로 운영 코드의 리소스 탐색기를 검증한다.
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        PropertySourcesPlaceholderConfigurer configurer;
        try {
            Thread.currentThread().setContextClassLoader(new ClassLoader(original) {
                @Override
                public URL getResource(String name) {
                    if (name.equals("db.properties") || name.equals("redis.properties")) {
                        return original.getResource("configuration/properties/"
                            + name.replace(".properties", "-fixture.properties"));
                    }
                    return super.getResource(name);
                }

                @Override
                public Enumeration<URL> getResources(String name) throws IOException {
                    if (name.equals("jwt.properties")) {
                        return includeJwt ? Collections.enumeration(List.of(
                            new ClassPathResource("configuration/properties/jwt-first.properties").getURL(),
                            new ClassPathResource("configuration/properties/jwt-second.properties").getURL()))
                            : Collections.emptyEnumeration();
                    }
                    return super.getResources(name);
                }
            });
            configurer = RootConfiguration.propertyPlaceholderConfigurer();
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
        var context = new AnnotationConfigApplicationContext();
        context.setEnvironment(environment);
        context.registerBean("propertyPlaceholderConfigurer", PropertySourcesPlaceholderConfigurer.class,
            () -> configurer);
        context.register(beans);
        return context;
    }

    static class Probe {
        @Value("${migration.layer}") String layer;
        @Value("${migration.db-only}") String dbOnly;
        @Value("${migration.redis-only}") String redisOnly;
        @Value("${migration.jwt-first-only}") String jwtFirstOnly;
        @Value("${app.cors.allowed-origin:http://localhost:5173}") String origin;
        @Value("${JWT_ACCESS_TOKEN_SECRET}") String secret;
    }
}
