import http from 'k6/http';
import { check, fail } from 'k6';

import { isUserCardListData, parseJson } from './checks.js';

export function bearerParams(token, endpoint, phase) {
    return {
        headers: {
            Accept: 'application/json',
            Authorization: `Bearer ${token}`,
        },
        tags: { endpoint, phase },
    };
}

export function loginAccount(baseUrl, email, password) {
    const issuedAtMs = Date.now();
    const response = http.post(
        `${baseUrl}/api/auth/login`,
        JSON.stringify({ email, password }),
        {
            headers: {
                Accept: 'application/json',
                'Content-Type': 'application/json',
            },
            tags: { endpoint: 'auth_login', phase: 'setup' },
        },
    );
    const body = parseJson(response);
    const data = body && body.data;

    const valid = check(response, {
        'auth_login: status is 200': (res) => res.status === 200,
        'auth_login: success envelope is valid': () => body !== null
            && body.success === true
            && body.code === 'SUCCESS',
        'auth_login: access token is present': () => data !== null
            && typeof data === 'object'
            && typeof data.accessToken === 'string'
            && data.accessToken.length > 0
            && data.tokenType === 'Bearer'
            && Number(data.expiresIn) > 0,
    }, { endpoint: 'auth_login', phase: 'setup' });

    if (!valid) {
        fail(`Login failed for performance account ${email} (status ${response.status})`);
    }

    return {
        email,
        token: data.accessToken,
        expiresIn: Number(data.expiresIn),
        issuedAtMs,
    };
}

export function prepareAuthenticatedAccounts(baseUrl, emails, password, profileSeconds) {
    const accounts = [];

    for (const email of emails) {
        const account = loginAccount(baseUrl, email, password);
        const response = http.get(
            `${baseUrl}/api/user-cards`,
            bearerParams(account.token, 'user_cards_list', 'setup'),
        );
        const body = parseJson(response);
        const valid = check(response, {
            'setup user_cards_list: status is 200': (res) => res.status === 200,
            'setup user_cards_list: response has usable card IDs': () => body !== null
                && body.success === true
                && isUserCardListData(body.data)
                && body.data.userCards.length > 0,
        }, { endpoint: 'user_cards_list', phase: 'setup' });

        if (!valid) {
            fail(`No usable active cards found for performance account ${email}`);
        }

        accounts.push({
            ...account,
            cardIds: body.data.userCards.map((card) => card.userCardId),
        });
    }

    const nowMs = Date.now();
    for (const account of accounts) {
        const remainingSeconds = account.expiresIn - ((nowMs - account.issuedAtMs) / 1000);
        if (remainingSeconds <= profileSeconds) {
            fail(
                `Access token for ${account.email} has only ${remainingSeconds.toFixed(1)}s remaining; `
                + `the ${profileSeconds}s profile would outlive it`,
            );
        }
    }

    return { accounts };
}
