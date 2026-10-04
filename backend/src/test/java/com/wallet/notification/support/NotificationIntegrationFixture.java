package com.wallet.notification.support;

import java.time.Clock;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 알림 특성 통합 테스트가 실제 MySQL에 독립적인 최소 데이터를 만들고 정리하는 픽스처다.
 *
 * <p>캐시 후처리는 DB 커밋 뒤에 실행되므로 테스트 전체를 롤백 트랜잭션으로 감싸지 않는다.
 * 대신 매 시나리오마다 고유 식별자를 사용하고 FK 역순으로 데이터를 삭제한다.</p>
 */
public final class NotificationIntegrationFixture {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String fixtureId = UUID.randomUUID().toString().replace("-", "");
    private final List<Long> memberIds = new ArrayList<>();
    private final List<Long> companyIds = new ArrayList<>();
    private final List<Long> cardIds = new ArrayList<>();
    private final List<Long> userCardIds = new ArrayList<>();
    private final List<Long> benefitIds = new ArrayList<>();
    private int sequence;

    public NotificationIntegrationFixture(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Scenario createBenefitLimitScenario(int benefitCount, long usedAmount) {
        int scenarioNumber = ++sequence;
        String suffix = fixtureId + scenarioNumber;
        String shortSuffix = fixtureId.substring(0, 20) + scenarioNumber;

        jdbc.update(
            "INSERT INTO member (email, password_hash, name, nickname, member_status) "
                + "VALUES (?, 'x', '알림 특성 테스트', ?, 'ACTIVE')",
            "notification-characterization-" + suffix + "@example.com",
            "알림IT" + scenarioNumber
        );
        long memberId = jdbc.queryForObject(
            "SELECT member_id FROM member WHERE email = ?",
            Long.class,
            "notification-characterization-" + suffix + "@example.com"
        );
        memberIds.add(memberId);

        jdbc.update(
            "INSERT INTO card_company (company_code, company_name, is_active) VALUES (?, ?, 'Y')",
            "NOTI_" + shortSuffix,
            "알림테스트카드사" + shortSuffix
        );
        long companyId = jdbc.queryForObject(
            "SELECT card_company_id FROM card_company WHERE company_code = ?",
            Long.class,
            "NOTI_" + shortSuffix
        );
        companyIds.add(companyId);

        String cardName = "알림 특성 카드 " + suffix;
        jdbc.update(
            "INSERT INTO card (card_company_id, card_name, card_type, annual_fee, is_active) "
                + "VALUES (?, ?, 'CREDIT', 0, 'Y')",
            companyId,
            cardName
        );
        long cardId = jdbc.queryForObject(
            "SELECT card_id FROM card WHERE card_company_id = ? AND card_name = ?",
            Long.class,
            companyId,
            cardName
        );
        cardIds.add(cardId);

        jdbc.update(
            "INSERT INTO user_card "
                + "(member_id, card_id, masked_card_number, is_representative, card_status) "
                + "VALUES (?, ?, ?, 1, 'ACTIVE')",
            memberId,
            cardId,
            "****-****-****-" + String.format("%04d", scenarioNumber)
        );
        long userCardId = jdbc.queryForObject(
            "SELECT user_card_id FROM user_card WHERE member_id = ? AND card_id = ?",
            Long.class,
            memberId,
            cardId
        );
        userCardIds.add(userCardId);

        jdbc.update(
            "INSERT INTO performance_tier "
                + "(card_id, period_type, min_performance_amount, shared_monthly_limit) "
                + "VALUES (?, 'MONTH', 0, NULL)",
            cardId
        );

        List<Long> scenarioBenefitIds = new ArrayList<>();
        for (int index = 1; index <= benefitCount; index++) {
            String benefitName = "알림 한도 혜택 " + suffix + "-" + index;
            jdbc.update(
                "INSERT INTO benefit "
                    + "(card_id, benefit_name, benefit_kind, calc_method, benefit_value, "
                    + "target_type, require_performance, performance_period, monthly_limit, "
                    + "use_shared_limit, exclude_from_performance, is_active) "
                    + "VALUES (?, ?, 'DISCOUNT', 'RATE', 10.00, 'ALL', 'N', 'MONTH', "
                    + "10000, 'N', 'N', 'Y')",
                cardId,
                benefitName
            );
            long benefitId = jdbc.queryForObject(
                "SELECT benefit_id FROM benefit WHERE card_id = ? AND benefit_name = ?",
                Long.class,
                cardId,
                benefitName
            );
            benefitIds.add(benefitId);
            scenarioBenefitIds.add(benefitId);

            jdbc.update(
                "INSERT INTO user_benefit_usage "
                    + "(user_card_id, benefit_id, base_year_month, used_amount, used_count) "
                    + "VALUES (?, ?, ?, ?, 1)",
                userCardId,
                benefitId,
                YearMonth.now(clock).toString(),
                usedAmount
            );
        }

        return new Scenario(memberId, companyId, cardId, userCardId, List.copyOf(scenarioBenefitIds));
    }

    public long createStandaloneMember() {
        int scenarioNumber = ++sequence;
        String email = "notification-cache-" + fixtureId + scenarioNumber + "@example.com";
        jdbc.update(
            "INSERT INTO member (email, password_hash, name, nickname, member_status) "
                + "VALUES (?, 'x', '알림 캐시 테스트', ?, 'ACTIVE')",
            email,
            "캐시IT" + scenarioNumber
        );
        long memberId = jdbc.queryForObject(
            "SELECT member_id FROM member WHERE email = ?",
            Long.class,
            email
        );
        memberIds.add(memberId);
        return memberId;
    }

    public long insertUnreadNotification(long memberId, String deduplicationKey) {
        jdbc.update(
            "INSERT INTO notification "
                + "(member_id, notification_type, title, content, notification_status, "
                + "sent_at, deduplication_key) "
                + "VALUES (?, 'BENEFIT_LIMIT', '캐시 대체 테스트', '캐시 대체 테스트', "
                + "'SENT', NOW(), ?)",
            memberId,
            deduplicationKey
        );
        return jdbc.queryForObject(
            "SELECT notification_id FROM notification WHERE member_id = ? AND deduplication_key = ?",
            Long.class,
            memberId,
            deduplicationKey
        );
    }

    public List<Long> memberIds() {
        return List.copyOf(memberIds);
    }

    public void cleanUp() {
        memberIds().forEach(memberId -> {
            jdbc.update("DELETE FROM notification WHERE member_id = ?", memberId);
            jdbc.update("DELETE FROM notification_setting WHERE member_id = ?", memberId);
        });

        userCardIds.forEach(userCardId -> {
            jdbc.update("DELETE FROM user_benefit_usage WHERE user_card_id = ?", userCardId);
            jdbc.update("DELETE FROM user_card_monthly_state WHERE user_card_id = ?", userCardId);
        });
        benefitIds.forEach(benefitId -> jdbc.update("DELETE FROM benefit WHERE benefit_id = ?", benefitId));
        cardIds.forEach(cardId -> jdbc.update("DELETE FROM performance_tier WHERE card_id = ?", cardId));
        userCardIds.forEach(userCardId -> jdbc.update("DELETE FROM user_card WHERE user_card_id = ?", userCardId));
        cardIds.forEach(cardId -> jdbc.update("DELETE FROM card WHERE card_id = ?", cardId));
        companyIds.forEach(companyId ->
            jdbc.update("DELETE FROM card_company WHERE card_company_id = ?", companyId));
        memberIds.forEach(memberId -> jdbc.update("DELETE FROM member WHERE member_id = ?", memberId));
    }

    public record Scenario(
        long memberId,
        long companyId,
        long cardId,
        long userCardId,
        List<Long> benefitIds
    ) {
    }
}
