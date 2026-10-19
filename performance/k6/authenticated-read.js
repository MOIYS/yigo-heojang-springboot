import http from 'k6/http';
import { sleep } from 'k6';

import {
    authenticatedReadOptions,
    BASE_URL,
    PERF_ACCOUNT_COUNT,
    requirePerfPassword,
    TOTAL_PROFILE_SECONDS,
} from './config.js';
import { bearerParams, prepareAuthenticatedAccounts } from './lib/auth.js';
import {
    isMemberData,
    isNotificationListData,
    isUserCardDetailData,
    isUserCardListData,
    verifyApiResponse,
} from './lib/checks.js';
import {
    accountEmails,
    randomThinkSeconds,
    selectAccount,
    selectEndpoint,
    selectNotificationPage,
} from './lib/data.js';

export const options = authenticatedReadOptions;

export function setup() {
    return prepareAuthenticatedAccounts(
        BASE_URL,
        accountEmails(PERF_ACCOUNT_COUNT),
        requirePerfPassword(),
        TOTAL_PROFILE_SECONDS,
    );
}

function get(account, path, endpoint, phase, validator) {
    const response = http.get(
        `${BASE_URL}${path}`,
        bearerParams(account.token, endpoint, phase),
    );
    return verifyApiResponse(response, endpoint, phase, validator);
}

function runReadIteration(data, phase) {
    const account = selectAccount(data.accounts, __VU, __ITER);
    const endpoint = selectEndpoint(Math.random());

    if (endpoint === 'members_me') {
        get(account, '/api/members/me', endpoint, phase, isMemberData);
    } else if (endpoint === 'user_cards_list') {
        get(account, '/api/user-cards', endpoint, phase, isUserCardListData);
    } else if (endpoint === 'user_cards_detail') {
        const cardId = account.cardIds[__ITER % account.cardIds.length];
        get(
            account,
            `/api/user-cards/${cardId}`,
            endpoint,
            phase,
            (detail) => isUserCardDetailData(detail) && detail.userCardId === cardId,
        );
    } else {
        const page = selectNotificationPage(Math.random());
        get(
            account,
            `/api/notifications?page=${page}&size=10`,
            endpoint,
            phase,
            isNotificationListData,
        );
    }

    sleep(randomThinkSeconds());
}

export function warmup(data) {
    runReadIteration(data, 'warmup');
}

export function baseline(data) {
    runReadIteration(data, 'baseline');
}

export function cooldown(data) {
    runReadIteration(data, 'cooldown');
}
