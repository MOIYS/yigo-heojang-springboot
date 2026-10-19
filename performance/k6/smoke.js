import http from 'k6/http';

import {
    BASE_URL,
    PERF_ACCOUNT_COUNT,
    requirePerfPassword,
    smokeOptions,
} from './config.js';
import { bearerParams, loginAccount } from './lib/auth.js';
import {
    isMemberData,
    isNotificationListData,
    isUserCardDetailData,
    isUserCardListData,
    verifyApiResponse,
} from './lib/checks.js';
import { accountEmails } from './lib/data.js';

export const options = smokeOptions;

export function setup() {
    const email = accountEmails(PERF_ACCOUNT_COUNT)[0];
    return loginAccount(BASE_URL, email, requirePerfPassword());
}

function get(account, path, endpoint, validator) {
    const response = http.get(
        `${BASE_URL}${path}`,
        bearerParams(account.token, endpoint, 'smoke'),
    );
    return verifyApiResponse(response, endpoint, 'smoke', validator);
}

export default function (account) {
    get(account, '/api/members/me', 'members_me', isMemberData);

    const cards = get(
        account,
        '/api/user-cards',
        'user_cards_list',
        (data) => isUserCardListData(data) && data.userCards.length > 0,
    );
    const cardIds = cards.body && cards.body.data && Array.isArray(cards.body.data.userCards)
        ? cards.body.data.userCards.map((card) => card.userCardId)
        : [];

    if (cardIds.length > 0) {
        const cardId = cardIds[0];
        get(
            account,
            `/api/user-cards/${cardId}`,
            'user_cards_detail',
            (detail) => isUserCardDetailData(detail) && detail.userCardId === cardId,
        );
    }

    get(
        account,
        '/api/notifications?page=0&size=10',
        'notifications_list',
        isNotificationListData,
    );
}
