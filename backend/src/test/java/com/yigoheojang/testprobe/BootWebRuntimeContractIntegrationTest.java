package com.yigoheojang.testprobe;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import com.wallet.auth.filter.JwtAuthenticationFilter;
import com.wallet.boot.WalletApplication;

@AutoConfigureTestRestTemplate
@SpringBootTest(
    classes = {
        WalletApplication.class,
        BootWebRuntimeContractIntegrationTest.TestProbeConfiguration.class
    },
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "JWT_ACCESS_TOKEN_SECRET=test-access-token-secret-at-least-32-bytes",
        "JWT_REFRESH_TOKEN_SECRET=test-refresh-token-secret-at-least-32-bytes"
    }
)
class BootWebRuntimeContractIntegrationTest {

    @Value("${app.cors.allowed-origin:http://localhost:5173}")
    private String frontendOrigin;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ServletContext servletContext;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private RequestMappingHandlerAdapter handlerAdapter;

    @Autowired
    @Qualifier("objectMapper")
    private ObjectMapper objectMapper;

    @Test
    void onlyOneMvcConfigurationAndTheConfiguredObjectMapperDriveJsonResponses() throws Exception {
        assertThat(applicationContext.getBeansOfType(WebMvcAutoConfiguration.class)).isEmpty();

        List<MappingJackson2HttpMessageConverter> jacksonConverters = handlerAdapter
            .getMessageConverters()
            .stream()
            .filter(MappingJackson2HttpMessageConverter.class::isInstance)
            .map(MappingJackson2HttpMessageConverter.class::cast)
            .toList();

        assertThat(jacksonConverters).isNotEmpty();
        assertThat(jacksonConverters)
            .allSatisfy(converter -> assertThat(converter.getObjectMapper()).isSameAs(objectMapper));

        ResponseEntity<String> response = restTemplate.getForEntity("/test-only/date", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"value\":[2026,10,26,12,34,56]}");
        assertThat(objectMapper.writeValueAsString(Map.of(
            "value", LocalDateTime.of(2026, 10, 26, 12, 34, 56)
        ))).isEqualTo(response.getBody());
    }

    @Test
    void servletContainerRegistersEachFilterExactlyOnce() {
        Map<String, ? extends FilterRegistration> registrations = servletContext.getFilterRegistrations();

        assertThat(registrations).containsKeys("encodingFilter", "jwtAuthenticationFilter");
        assertThat(registrations.values())
            .filteredOn(registration ->
                CharacterEncodingFilter.class.getName().equals(registration.getClassName()))
            .hasSize(1);
        assertThat(registrations.values())
            .filteredOn(registration ->
                JwtAuthenticationFilter.class.getName().equals(registration.getClassName()))
            .hasSize(1);
        assertThat(registrations.get("encodingFilter").getUrlPatternMappings())
            .containsExactly("/*");
        assertThat(registrations.get("jwtAuthenticationFilter").getUrlPatternMappings())
            .containsExactly("/api/*");
    }

    @Test
    void preflightKeepsCorsContractAndTrailingSlashPathDoesNotMatch() {
        ResponseEntity<String> response = preflight("/api/terms", frontendOrigin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNullOrEmpty();
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(frontendOrigin);
        assertThat(response.getHeaders().getAccessControlAllowMethods()).containsExactly(
            HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
            HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.OPTIONS
        );
        assertThat(response.getHeaders().getAccessControlAllowHeaders())
            .containsExactlyInAnyOrder("Authorization", "Content-Type");
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isTrue();
        assertThat(response.getHeaders().getAccessControlMaxAge()).isEqualTo(1800);

        ResponseEntity<String> trailingSlashResponse = restTemplate.getForEntity(
            "/test-only/date/",
            String.class
        );
        assertThat(trailingSlashResponse.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void preflightRejectsUnallowedOriginWithoutAllowOriginHeader() {
        ResponseEntity<String> response = preflight("/api/terms", "https://unallowed.example.invalid");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }

    @Test
    void jwtFilterAppliesOnlyToApiUrlPattern() {
        ResponseEntity<String> apiResponse = restTemplate.getForEntity("/api/not-found", String.class);
        ResponseEntity<String> nonApiResponse = restTemplate.getForEntity(
            "/test-only/date",
            String.class
        );

        assertThat(apiResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(apiResponse.getBody()).contains("\"code\":\"ACCESS_TOKEN_INVALID\"");
        assertThat(nonApiResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void characterEncodingFilterDecodesUtf8FormBody() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<String> request = new HttpEntity<>(
            "value=%ED%95%9C%EA%B8%80-%EC%9D%B8%EC%BD%94%EB%94%A9",
            headers
        );

        ResponseEntity<String> response = restTemplate.postForEntity(
            "/test-only/encoding",
            request,
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
            .isEqualTo(MediaType.parseMediaType("text/plain;charset=UTF-8"));
        assertThat(response.getBody()).isEqualTo("한글-인코딩");
    }

    private ResponseEntity<String> preflight(String path, String origin) {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(origin);
        headers.setAccessControlRequestMethod(HttpMethod.GET);
        headers.setAccessControlRequestHeaders(List.of("Authorization", "Content-Type"));
        return restTemplate.exchange(
            path,
            HttpMethod.OPTIONS,
            new HttpEntity<>(headers),
            String.class
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestProbeConfiguration {

        @Bean
        TestProbeController testProbeController() {
            return new TestProbeController();
        }
    }

    @RestController
    static class TestProbeController {

        @GetMapping("/test-only/date")
        Map<String, LocalDateTime> date() {
            return Map.of("value", LocalDateTime.of(2026, 10, 26, 12, 34, 56));
        }

        @PostMapping(
            value = "/test-only/encoding",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE
        )
        String encoding(@RequestParam String value) {
            return value;
        }
    }
}
