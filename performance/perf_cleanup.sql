-- Issue #11 / task 2: remove only the exact performance-test accounts.
-- MySQL 8.0 only. This does not reset AUTO_INCREMENT values.

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS perf_cleanup_issue_11;

DELIMITER //

CREATE PROCEDURE perf_cleanup_issue_11()
BEGIN
    DECLARE v_count INT DEFAULT 0;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    DROP TEMPORARY TABLE IF EXISTS perf_cleanup_accounts;
    CREATE TEMPORARY TABLE perf_cleanup_accounts (
        user_no   INT          NOT NULL PRIMARY KEY,
        email     VARCHAR(255) NOT NULL UNIQUE,
        member_id BIGINT       NULL
    ) ENGINE = MEMORY;

    INSERT INTO perf_cleanup_accounts (user_no, email)
    WITH RECURSIVE seq(user_no) AS (
        SELECT 1
        UNION ALL
        SELECT user_no + 1 FROM seq WHERE user_no < 20
    )
    SELECT
        user_no,
        CONCAT('perf-user-', LPAD(user_no, 3, '0'), '@example.invalid')
    FROM seq;

    UPDATE perf_cleanup_accounts a
    JOIN member m ON m.email = a.email
    SET a.member_id = m.member_id;

    START TRANSACTION;

    DELETE n
    FROM notification n
    JOIN perf_cleanup_accounts a ON a.member_id = n.member_id;

    DELETE rt
    FROM refresh_token rt
    JOIN perf_cleanup_accounts a ON a.member_id = rt.member_id;

    DELETE uc
    FROM user_card uc
    JOIN perf_cleanup_accounts a ON a.member_id = uc.member_id;

    DELETE m
    FROM member m
    JOIN perf_cleanup_accounts a ON a.member_id = m.member_id;

    SELECT COUNT(*) INTO v_count
    FROM member m
    JOIN perf_cleanup_accounts a ON a.email = m.email;

    IF v_count <> 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Perf cleanup validation failed: performance members remain';
    END IF;

    COMMIT;

    SELECT 20 AS targeted_accounts, 0 AS remaining_accounts;
END//

DELIMITER ;

CALL perf_cleanup_issue_11();
DROP PROCEDURE IF EXISTS perf_cleanup_issue_11;
