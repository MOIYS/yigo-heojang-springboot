-- Issue #11 / task 2: deterministic data for Legacy WAR performance tests.
-- MySQL 8.0 only. Run against an isolated local performance database.
--
-- The caller must set @perf_password_hash to a 60-character BCrypt hash in the
-- same connection. No password or reusable credential is stored in this file.

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS perf_seed_issue_11;

DELIMITER //

CREATE PROCEDURE perf_seed_issue_11()
BEGIN
    DECLARE v_count INT DEFAULT 0;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    IF @perf_password_hash IS NULL
       OR CHAR_LENGTH(@perf_password_hash) <> 60
       OR LEFT(@perf_password_hash, 4) NOT IN ('$2a$', '$2b$', '$2y$') THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Set @perf_password_hash to a 60-character BCrypt hash before running perf_seed.sql';
    END IF;

    DROP TEMPORARY TABLE IF EXISTS perf_seed_accounts;
    CREATE TEMPORARY TABLE perf_seed_accounts (
        user_no   INT          NOT NULL PRIMARY KEY,
        email     VARCHAR(255) NOT NULL UNIQUE,
        member_id BIGINT       NULL
    ) ENGINE = MEMORY;

    INSERT INTO perf_seed_accounts (user_no, email)
    WITH RECURSIVE seq(user_no) AS (
        SELECT 1
        UNION ALL
        SELECT user_no + 1 FROM seq WHERE user_no < 20
    )
    SELECT
        user_no,
        CONCAT('perf-user-', LPAD(user_no, 3, '0'), '@example.invalid')
    FROM seq;

    DROP TEMPORARY TABLE IF EXISTS perf_seed_card_keys;
    CREATE TEMPORARY TABLE perf_seed_card_keys (
        card_no      INT          NOT NULL PRIMARY KEY,
        company_code VARCHAR(30)  NOT NULL,
        card_name    VARCHAR(100) NOT NULL,
        UNIQUE KEY uk_perf_seed_card_key (company_code, card_name)
    ) ENGINE = MEMORY;

    INSERT INTO perf_seed_card_keys (card_no, company_code, card_name) VALUES
        (1, 'KB_CARD',      'ALL point 카드'),
        (2, 'SHINHAN_CARD', '신한카드 핏(Fit)'),
        (3, 'SAMSUNG_CARD', '삼성 iD ON 카드'),
        (4, 'LOTTE_CARD',   'LOCA 365 카드'),
        (5, 'HANA_CARD',    'JADE Classic');

    SELECT COUNT(*) INTO v_count
    FROM (
        SELECT k.card_no
        FROM perf_seed_card_keys k
        LEFT JOIN card_company cc
            ON cc.company_code = k.company_code
        LEFT JOIN card c
            ON c.card_company_id = cc.card_company_id
           AND c.card_name = k.card_name
        GROUP BY k.card_no
        HAVING COUNT(c.card_id) <> 1
    ) invalid_card_keys;

    IF v_count <> 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Each perf card key must resolve to exactly one card master row';
    END IF;

    DROP TEMPORARY TABLE IF EXISTS perf_seed_cards;
    CREATE TEMPORARY TABLE perf_seed_cards (
        card_no INT    NOT NULL PRIMARY KEY,
        card_id BIGINT NOT NULL UNIQUE
    ) ENGINE = MEMORY;

    INSERT INTO perf_seed_cards (card_no, card_id)
    SELECT k.card_no, c.card_id
    FROM perf_seed_card_keys k
    JOIN card_company cc
        ON cc.company_code = k.company_code
    JOIN card c
        ON c.card_company_id = cc.card_company_id
       AND c.card_name = k.card_name;

    SELECT COUNT(*) INTO v_count
    FROM perf_seed_cards pc
    JOIN card c ON c.card_id = pc.card_id
    JOIN card_company cc ON cc.card_company_id = c.card_company_id
    WHERE c.is_active <> 'Y' OR cc.is_active <> 'Y';

    IF v_count <> 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'All perf card master rows and companies must be active';
    END IF;

    START TRANSACTION;

    -- Re-running replaces only the exact twenty performance accounts.
    UPDATE perf_seed_accounts a
    JOIN member m ON m.email = a.email
    SET a.member_id = m.member_id;

    DELETE n
    FROM notification n
    JOIN perf_seed_accounts a ON a.member_id = n.member_id;

    DELETE rt
    FROM refresh_token rt
    JOIN perf_seed_accounts a ON a.member_id = rt.member_id;

    DELETE uc
    FROM user_card uc
    JOIN perf_seed_accounts a ON a.member_id = uc.member_id;

    DELETE m
    FROM member m
    JOIN perf_seed_accounts a ON a.member_id = m.member_id;

    UPDATE perf_seed_accounts SET member_id = NULL;

    INSERT INTO member (
        email,
        password_hash,
        name,
        nickname,
        member_status,
        created_at,
        updated_at,
        withdrawn_at
    )
    SELECT
        email,
        @perf_password_hash,
        CONCAT('Performance User ', LPAD(user_no, 3, '0')),
        CONCAT('perf-', LPAD(user_no, 3, '0')),
        'ACTIVE',
        TIMESTAMPADD(MINUTE, user_no - 1, '2026-10-01 00:00:00'),
        TIMESTAMPADD(MINUTE, user_no - 1, '2026-10-01 00:00:00'),
        NULL
    FROM perf_seed_accounts
    ORDER BY user_no;

    UPDATE perf_seed_accounts a
    JOIN member m ON m.email = a.email
    SET a.member_id = m.member_id;

    INSERT INTO user_card (
        member_id,
        card_id,
        masked_card_number,
        is_representative,
        registered_at,
        card_status
    )
    SELECT
        a.member_id,
        pc.card_id,
        CONCAT('****-****-****-', LPAD(7000 + (a.user_no * 10) + pc.card_no, 4, '0')),
        CASE WHEN pc.card_no = 1 THEN 1 ELSE 0 END,
        TIMESTAMPADD(SECOND, ((a.user_no - 1) * 10) + pc.card_no, '2026-10-01 01:00:00'),
        'ACTIVE'
    FROM perf_seed_accounts a
    CROSS JOIN perf_seed_cards pc
    ORDER BY a.user_no, pc.card_no;

    DROP TEMPORARY TABLE IF EXISTS perf_seed_notification_numbers;
    CREATE TEMPORARY TABLE perf_seed_notification_numbers (
        notification_no INT NOT NULL PRIMARY KEY
    ) ENGINE = MEMORY;

    INSERT INTO perf_seed_notification_numbers (notification_no)
    WITH RECURSIVE seq(notification_no) AS (
        SELECT 1
        UNION ALL
        SELECT notification_no + 1 FROM seq WHERE notification_no < 40
    )
    SELECT notification_no FROM seq;

    INSERT INTO notification (
        member_id,
        user_card_id,
        benefit_id,
        point_history_id,
        notification_type,
        title,
        content,
        notification_status,
        scheduled_at,
        sent_at,
        created_at,
        read_at,
        deleted_at,
        deduplication_key
    )
    SELECT
        a.member_id,
        uc.user_card_id,
        NULL,
        NULL,
        CASE
            WHEN MOD(nn.notification_no, 2) = 0 THEN 'BENEFIT_LIMIT'
            ELSE 'PERFORMANCE_SHORTAGE'
        END,
        CONCAT('Performance notification ', LPAD(nn.notification_no, 2, '0')),
        CONCAT('Deterministic notification for performance account ', LPAD(a.user_no, 3, '0')),
        'SENT',
        TIMESTAMPADD(
            SECOND,
            ((a.user_no - 1) * 3600) + (nn.notification_no * 60),
            '2026-10-01 02:00:00'
        ),
        TIMESTAMPADD(
            SECOND,
            ((a.user_no - 1) * 3600) + (nn.notification_no * 60),
            '2026-10-01 02:00:00'
        ),
        TIMESTAMPADD(
            SECOND,
            ((a.user_no - 1) * 3600) + (nn.notification_no * 60),
            '2026-10-01 02:00:00'
        ),
        CASE
            WHEN nn.notification_no <= 15 THEN TIMESTAMPADD(
                SECOND,
                ((a.user_no - 1) * 3600) + (nn.notification_no * 60) + 30,
                '2026-10-01 02:00:00'
            )
            ELSE NULL
        END,
        CASE
            WHEN nn.notification_no > 35 THEN TIMESTAMPADD(
                SECOND,
                ((a.user_no - 1) * 3600) + (nn.notification_no * 60) + 45,
                '2026-10-01 02:00:00'
            )
            ELSE NULL
        END,
        CONCAT(
            'PERF-SEED-I11-U', LPAD(a.user_no, 3, '0'),
            '-N', LPAD(nn.notification_no, 3, '0')
        )
    FROM perf_seed_accounts a
    CROSS JOIN perf_seed_notification_numbers nn
    JOIN perf_seed_cards pc
        ON pc.card_no = MOD(nn.notification_no - 1, 5) + 1
    JOIN user_card uc
        ON uc.member_id = a.member_id
       AND uc.card_id = pc.card_id
    ORDER BY a.user_no, nn.notification_no;

    SELECT COUNT(*) INTO v_count
    FROM member m
    JOIN perf_seed_accounts a ON a.member_id = m.member_id
    WHERE m.member_status = 'ACTIVE';
    IF v_count <> 20 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Perf seed validation failed: expected 20 active members';
    END IF;

    SELECT COUNT(*) INTO v_count
    FROM user_card uc
    JOIN perf_seed_accounts a ON a.member_id = uc.member_id
    WHERE uc.card_status = 'ACTIVE';
    IF v_count <> 100 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Perf seed validation failed: expected 100 active user cards';
    END IF;

    SELECT COUNT(*) INTO v_count
    FROM user_card uc
    JOIN perf_seed_accounts a ON a.member_id = uc.member_id
    WHERE uc.card_status = 'ACTIVE' AND uc.is_representative = 1;
    IF v_count <> 20 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Perf seed validation failed: expected 20 representative cards';
    END IF;

    SELECT COUNT(*) INTO v_count
    FROM (
        SELECT a.member_id
        FROM perf_seed_accounts a
        JOIN user_card uc ON uc.member_id = a.member_id
        WHERE uc.card_status = 'ACTIVE'
        GROUP BY a.member_id
        HAVING SUM(uc.is_representative = 1) <> 1
    ) invalid_representatives;
    IF v_count <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Perf seed validation failed: each member needs one representative card';
    END IF;

    SELECT COUNT(*) INTO v_count
    FROM notification n
    JOIN perf_seed_accounts a ON a.member_id = n.member_id
    WHERE n.deleted_at IS NULL;
    IF v_count <> 700 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Perf seed validation failed: expected 700 active notifications';
    END IF;

    SELECT COUNT(*) INTO v_count
    FROM notification n
    JOIN perf_seed_accounts a ON a.member_id = n.member_id
    WHERE n.deleted_at IS NOT NULL;
    IF v_count <> 100 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Perf seed validation failed: expected 100 deleted notifications';
    END IF;

    SELECT COUNT(*) INTO v_count
    FROM notification n
    JOIN perf_seed_accounts a ON a.member_id = n.member_id
    WHERE n.deleted_at IS NULL AND n.read_at IS NOT NULL;
    IF v_count <> 300 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Perf seed validation failed: expected 300 active read notifications';
    END IF;

    SELECT COUNT(*) INTO v_count
    FROM notification n
    JOIN perf_seed_accounts a ON a.member_id = n.member_id
    WHERE n.deleted_at IS NULL AND n.read_at IS NULL;
    IF v_count <> 400 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Perf seed validation failed: expected 400 active unread notifications';
    END IF;

    SELECT COUNT(*) INTO v_count
    FROM notification n
    JOIN perf_seed_accounts a ON a.member_id = n.member_id
    LEFT JOIN user_card uc
        ON uc.user_card_id = n.user_card_id
       AND uc.member_id = n.member_id
    WHERE uc.user_card_id IS NULL;
    IF v_count <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Perf seed validation failed: notification card relationship is invalid';
    END IF;

    COMMIT;

    SELECT
        20 AS active_members,
        100 AS active_user_cards,
        20 AS representative_cards,
        700 AS active_notifications,
        300 AS active_read_notifications,
        400 AS active_unread_notifications,
        100 AS deleted_notifications;
END//

DELIMITER ;

CALL perf_seed_issue_11();
DROP PROCEDURE IF EXISTS perf_seed_issue_11;
