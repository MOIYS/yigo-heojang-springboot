export function accountEmails(accountCount) {
    if (__ENV.PERF_EMAILS) {
        const emails = __ENV.PERF_EMAILS
            .split(',')
            .map((email) => email.trim())
            .filter((email) => email.length > 0);
        if (emails.length === 0) {
            throw new Error('PERF_EMAILS was supplied but contains no email addresses');
        }
        return emails;
    }

    return Array.from({ length: accountCount }, (_, index) => {
        const number = String(index + 1).padStart(3, '0');
        return `perf-user-${number}@example.invalid`;
    });
}

export function selectAccount(accounts, vuNumber, iterationNumber) {
    return accounts[(vuNumber - 1 + iterationNumber) % accounts.length];
}

export function selectEndpoint(randomValue) {
    if (randomValue < 0.15) {
        return 'members_me';
    }
    if (randomValue < 0.45) {
        return 'user_cards_list';
    }
    if (randomValue < 0.70) {
        return 'user_cards_detail';
    }
    return 'notifications_list';
}

export function selectNotificationPage(randomValue) {
    if (randomValue < 0.80) {
        return 0;
    }
    if (randomValue < 0.95) {
        return 1;
    }
    return 2;
}

export function randomThinkSeconds() {
    return 0.5 + Math.random();
}
