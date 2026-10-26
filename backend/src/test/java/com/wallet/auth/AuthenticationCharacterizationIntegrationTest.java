package com.wallet.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import javax.sql.DataSource;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ContextHierarchy;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.wallet.common.util.Sha256Hasher;

/**
 * Boot 전환 전에 레거시 인증 동작을 테스트로 고정한다.
 *
 * <p>실제 Spring MVC, Service, MyBatis와 MySQL을 사용하며 테스트 데이터는
 * 각 테스트의 트랜잭션이 끝날 때 롤백한다. Refresh Token 비회전과 재사용은
 * 현재 동작을 기록하기 위한 것으로, RTR 도입 시 의도적으로 변경될 대상이다.</p>
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextHierarchy({
    @ContextConfiguration(
        name = "root",
        locations = "file:src/main/webapp/WEB-INF/spring/root-context.xml"
    ),
    @ContextConfiguration(
        name = "dispatcher",
        locations = "file:src/main/webapp/WEB-INF/spring/appServlet/servlet-context.xml"
    )
})
@Transactional
class AuthenticationCharacterizationIntegrationTest {
    private static final String EMAIL = "legacy-auth@example.com";
    private static final String PASSWORD = "password123!";
    private static final String REFRESH_TOKEN_COOKIE = "refreshToken";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private DataSource dataSource;

    private MockMvc mockMvc;
    private JdbcTemplate jdbc;
    private Long memberId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        jdbc = new JdbcTemplate(dataSource);

        String passwordHash = new BCryptPasswordEncoder().encode(PASSWORD);
        jdbc.update(
            "INSERT INTO member "
                + "(email, password_hash, name, nickname, member_status) "
                + "VALUES (?, ?, ?, ?, 'ACTIVE')",
            EMAIL,
            passwordHash,
            "레거시 인증 사용자",
            "legacy-auth"
        );
        memberId = jdbc.queryForObject(
            "SELECT member_id FROM member WHERE email = ?",
            Long.class,
            EMAIL
        );
    }

    @Test
    @DisplayName("로그인 - Access Token을 응답하고 HttpOnly Refresh Token의 해시를 저장한다")
    void login_issuesAccessTokenAndRefreshCookie() throws Exception {
        // when
        LoginSession session = login();

        // then
        assertThat(session.accessToken()).isNotBlank();
        assertThat(session.setCookieHeader())
            .contains("refreshToken=")
            .contains("HttpOnly")
            .contains("Path=/")
            .contains("Max-Age=43200")
            .contains("SameSite=Lax")
            .doesNotContain("Secure");

        String storedHash = jdbc.queryForObject(
            "SELECT token_hash FROM refresh_token WHERE member_id = ?",
            String.class,
            memberId
        );
        assertThat(storedHash)
            .isEqualTo(new Sha256Hasher().sha256(session.refreshToken()))
            .isNotEqualTo(session.refreshToken());
        assertThat(activeRefreshTokenCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("토큰 재발급 - Access Token만 변경하고 동일 Refresh Token의 재사용을 허용한다")
    void reissue_legacy_changesOnlyAccessTokenAndAllowsRefreshTokenReuse() throws Exception {
        // given
        LoginSession session = login();
        waitUntilNextJwtSecond();

        // when
        MvcResult firstReissue = reissue(session.refreshToken());
        MvcResult secondReissue = reissue(session.refreshToken());

        // then
        String firstAccessToken = readAccessToken(firstReissue);
        String secondAccessToken = readAccessToken(secondReissue);
        assertThat(firstAccessToken).isNotEqualTo(session.accessToken());
        assertThat(secondAccessToken).isNotBlank();
        assertThat(activeRefreshTokenCount()).isEqualTo(1);
        assertThat(refreshTokenCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("재로그인 - 기존 Refresh Token을 REISSUED 사유로 폐기한다")
    void relogin_revokesPreviousRefreshToken() throws Exception {
        // given
        LoginSession firstSession = login();
        waitUntilNextJwtSecond();

        // when
        LoginSession secondSession = login();

        // then
        assertThat(secondSession.refreshToken()).isNotEqualTo(firstSession.refreshToken());
        assertThat(revokeReason(firstSession.refreshToken())).isEqualTo("REISSUED");
        assertThat(revokeReason(secondSession.refreshToken())).isNull();
        assertThat(activeRefreshTokenCount()).isEqualTo(1);
        assertRefreshFailure(firstSession.refreshToken());
    }

    @Test
    @DisplayName("로그아웃 - DB 토큰을 LOGOUT 사유로 폐기하고 Refresh Token 쿠키를 만료시킨다")
    void logout_revokesRefreshTokenAndExpiresCookie() throws Exception {
        // given
        LoginSession session = login();

        // when
        MvcResult result = mockMvc.perform(post("/api/auth/logout")
                .cookie(refreshTokenCookie(session.refreshToken())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andReturn();

        // then
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
            .contains("refreshToken=")
            .contains("Max-Age=0")
            .contains("HttpOnly")
            .contains("Path=/")
            .contains("SameSite=Lax");
        assertThat(revokeReason(session.refreshToken())).isEqualTo("LOGOUT");
        assertThat(activeRefreshTokenCount()).isZero();
        assertRefreshFailure(session.refreshToken());
    }

    @Test
    @DisplayName("토큰 재발급 실패 - 변조된 Refresh Token을 상태 코드와 오류 본문으로 거부한다")
    void reissue_rejectsTamperedRefreshToken() throws Exception {
        // given
        LoginSession session = login();
        String tamperedToken = tamperSignature(session.refreshToken());

        // when & then
        assertRefreshFailure(tamperedToken);
    }

    @Test
    @DisplayName("토큰 재발급 실패 - DB 유효기간이 지난 Refresh Token을 거부한다")
    void reissue_rejectsExpiredRefreshToken() throws Exception {
        // given
        LoginSession session = login();
        jdbc.update(
            "UPDATE refresh_token SET expires_at = DATE_SUB(NOW(), INTERVAL 1 SECOND) "
                + "WHERE token_hash = ?",
            new Sha256Hasher().sha256(session.refreshToken())
        );

        // when & then
        assertRefreshFailure(session.refreshToken());
    }

    private LoginSession login() throws Exception {
        String requestBody = "{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}";

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
            .andReturn();

        String setCookieHeader = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookieHeader).isNotBlank();
        return new LoginSession(
            readAccessToken(result),
            extractRefreshToken(setCookieHeader),
            setCookieHeader
        );
    }

    private MvcResult reissue(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/token")
                .cookie(refreshTokenCookie(refreshToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
            .andReturn();
    }

    private void assertRefreshFailure(String refreshToken) throws Exception {
        mockMvc.perform(post("/api/auth/token")
                .cookie(refreshTokenCookie(refreshToken)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_FAILED"))
            .andExpect(jsonPath("$.message").value("토큰 갱신에 실패했습니다."))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    private Cookie refreshTokenCookie(String refreshToken) {
        return new Cookie(REFRESH_TOKEN_COOKIE, refreshToken);
    }

    private String readAccessToken(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
    }

    private String extractRefreshToken(String setCookieHeader) {
        String cookiePair = setCookieHeader.substring(0, setCookieHeader.indexOf(';'));
        return cookiePair.substring((REFRESH_TOKEN_COOKIE + "=").length());
    }

    private String tamperSignature(String token) {
        int signatureStart = token.lastIndexOf('.') + 1;
        char original = token.charAt(signatureStart);
        char replacement = original == 'a' ? 'b' : 'a';
        return token.substring(0, signatureStart)
            + replacement
            + token.substring(signatureStart + 1);
    }

    private int refreshTokenCount() {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM refresh_token WHERE member_id = ?",
            Integer.class,
            memberId
        );
    }

    private int activeRefreshTokenCount() {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM refresh_token "
                + "WHERE member_id = ? AND revoked_at IS NULL AND expires_at > NOW()",
            Integer.class,
            memberId
        );
    }

    private String revokeReason(String refreshToken) {
        return jdbc.queryForObject(
            "SELECT revoke_reason FROM refresh_token WHERE token_hash = ?",
            String.class,
            new Sha256Hasher().sha256(refreshToken)
        );
    }

    private void waitUntilNextJwtSecond() throws InterruptedException {
        long issuedAtSecond = System.currentTimeMillis() / 1000;
        while (System.currentTimeMillis() / 1000 == issuedAtSecond) {
            Thread.sleep(10);
        }
    }

    private record LoginSession(
        String accessToken,
        String refreshToken,
        String setCookieHeader
    ) {}
}
