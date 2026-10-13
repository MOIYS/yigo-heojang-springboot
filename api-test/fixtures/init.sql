SET NAMES utf8mb4;
SET @member_email := CONCAT('newman-member-', @api_test_run_id, '@example.invalid');
SET @signup_email := CONCAT('newman-signup-', @api_test_run_id, '@example.invalid');
SET @signup_token := CONCAT('newman-signup-token-', @api_test_run_id);

START TRANSACTION;

INSERT INTO member (
    email,
    password_hash,
    name,
    nickname,
    member_status
) VALUES (
    @member_email,
    '$2a$10$gbXQpPN9TgBF1mbphznrRuNm1eRgCLYksV7iU4U6A87lPGq5mDsYG',
    'Newman API Test',
    CONCAT('newman-', @api_test_run_id),
    'ACTIVE'
);

SET @member_id := LAST_INSERT_ID();

INSERT INTO signup_email_verification (
    email,
    verification_code_hash,
    verification_status,
    failed_attempt_count,
    verification_code_expires_at,
    signup_token_hash,
    signup_token_expires_at,
    verified_at
) VALUES (
    @signup_email,
    SHA2(CONCAT('unused-verification-code-', @api_test_run_id), 256),
    'VERIFIED',
    0,
    DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 1 HOUR),
    SHA2(@signup_token, 256),
    DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 1 HOUR),
    CURRENT_TIMESTAMP
);

INSERT INTO notification (
    member_id,
    notification_type,
    title,
    content,
    notification_status,
    sent_at,
    deduplication_key
) VALUES
(
    @member_id,
    'BENEFIT_LIMIT',
    CONCAT('NEWMAN_READ_', @api_test_run_id),
    'Newman 읽음 처리 전용 알림',
    'SENT',
    CURRENT_TIMESTAMP,
    CONCAT('NEWMAN_READ_', @api_test_run_id)
),
(
    @member_id,
    'PERFORMANCE_SHORTAGE',
    CONCAT('NEWMAN_DELETE_', @api_test_run_id),
    'Newman 삭제 처리 전용 알림',
    'SENT',
    CURRENT_TIMESTAMP,
    CONCAT('NEWMAN_DELETE_', @api_test_run_id)
);

COMMIT;
