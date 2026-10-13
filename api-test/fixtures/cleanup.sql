SET NAMES utf8mb4;
SET @member_email := CONCAT('newman-member-', @api_test_run_id, '@example.invalid');
SET @signup_email := CONCAT('newman-signup-', @api_test_run_id, '@example.invalid');

START TRANSACTION;

DELETE n
FROM notification n
INNER JOIN member m ON m.member_id = n.member_id
WHERE m.email IN (@member_email, @signup_email);

DELETE uc
FROM user_card uc
INNER JOIN member m ON m.member_id = uc.member_id
WHERE m.email IN (@member_email, @signup_email);

DELETE rt
FROM refresh_token rt
INNER JOIN member m ON m.member_id = rt.member_id
WHERE m.email IN (@member_email, @signup_email);

DELETE mta
FROM member_term_agreement mta
INNER JOIN member m ON m.member_id = mta.member_id
WHERE m.email IN (@member_email, @signup_email);

DELETE FROM signup_email_verification
WHERE email = @signup_email;

DELETE FROM member
WHERE email IN (@member_email, @signup_email);

COMMIT;

