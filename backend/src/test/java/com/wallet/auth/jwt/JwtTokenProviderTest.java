package com.wallet.auth.jwt;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import com.wallet.member.domain.Member;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtTokenProviderTest {

    private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider();

    @Test
    @DisplayName("Access Token 생성 - 정상 회원 정보로 JWT를 생성한다")
    void createAccessToken_success() {
        // given
        Member member = createMember();

        // when
        String token = jwtTokenProvider.createAccessToken(member);

        // then
        assertThat(token).isNotNull();
        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("회원 ID 추출 - 생성된 토큰에서 memberId를 추출한다")
    void getMemberId_success() {
        // given
        Member member = createMember();
        String token = jwtTokenProvider.createAccessToken(member);

        // when
        Long memberId = jwtTokenProvider.getMemberId(token);

        // then
        assertThat(memberId).isEqualTo(1L);
    }

    @Test
    @DisplayName("토큰 유효시간 조회 - Access Token 만료 시간이 반환된다")
    void getAccessTokenValidityInSeconds_success() {
        // when
        long expiresIn = jwtTokenProvider.getAccessTokenValidityInSeconds();

        // then
        assertThat(expiresIn).isGreaterThan(0);
    }

    @Test
    @DisplayName("Refresh Token 생성 및 검증 - 회원 ID와 만료 시간을 포함한 유효한 JWT를 생성한다")
    void createAndValidateRefreshToken_success() {
        // given
        Long memberId = 1L;

        // when
        String token = jwtTokenProvider.createRefreshToken(memberId);

        // then
        assertThat(jwtTokenProvider.validateRefreshToken(token)).isTrue();
        assertThat(jwtTokenProvider.getMemberIdFromRefreshToken(token)).isEqualTo(memberId);
        assertThat(jwtTokenProvider.getRefreshTokenValidityInSeconds()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Refresh Token 변조 검증 - 서명이 변경된 토큰은 거부한다")
    void validateRefreshToken_fail_whenTokenIsTampered() {
        // given
        String token = jwtTokenProvider.createRefreshToken(1L);
        int signatureStart = token.lastIndexOf('.') + 1;
        char original = token.charAt(signatureStart);
        char replacement = original == 'a' ? 'b' : 'a';
        String tamperedToken = token.substring(0, signatureStart)
            + replacement
            + token.substring(signatureStart + 1);

        // when & then
        assertThat(jwtTokenProvider.validateRefreshToken(tamperedToken)).isFalse();
    }

    @Test
    @DisplayName("Refresh Token 만료 검증 - 만료 시간이 지난 토큰은 거부한다")
    void validateRefreshToken_fail_whenTokenIsExpired() {
        // given
        String refreshTokenSecret = (String) ReflectionTestUtils.getField(
            jwtTokenProvider,
            "refreshTokenSecret"
        );
        SecretKey signingKey = Keys.hmacShaKeyFor(
            refreshTokenSecret.getBytes(StandardCharsets.UTF_8)
        );
        Instant now = Instant.now();
        String expiredToken = Jwts.builder()
            .setSubject("1")
            .setIssuedAt(Date.from(now.minusSeconds(120)))
            .setExpiration(Date.from(now.minusSeconds(60)))
            .signWith(signingKey)
            .compact();

        // when & then
        assertThat(jwtTokenProvider.validateRefreshToken(expiredToken)).isFalse();
    }

    @Test
    @DisplayName("잘못된 토큰 검증 - 잘못된 JWT이면 예외가 발생한다")
    void getMemberId_fail_whenInvalidToken() {
        // given
        String invalidToken = "invalid.jwt.token";

        // when & then
        assertThrows(
            RuntimeException.class,
            () -> jwtTokenProvider.getMemberId(invalidToken)
        );
    }

    private Member createMember() {
        Member member = new Member();

        ReflectionTestUtils.setField(member, "memberId", 1L);
        ReflectionTestUtils.setField(member, "email", "user@example.com");
        ReflectionTestUtils.setField(member, "name", "이재혁");
        ReflectionTestUtils.setField(member, "memberStatus", "ACTIVE");
        return member;
    }
}
