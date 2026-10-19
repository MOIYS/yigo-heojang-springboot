import { check } from 'k6';

export function parseJson(response) {
    try {
        return response.json();
    } catch (_) {
        return null;
    }
}

function isSuccessEnvelope(body) {
    return body !== null
        && body.success === true
        && body.code === 'SUCCESS'
        && body.data !== null
        && typeof body.data === 'object';
}

export function verifyApiResponse(response, endpoint, phase, validateData) {
    const body = parseJson(response);
    const tags = { endpoint, phase };
    const passed = check(response, {
        [`${endpoint}: status is 200`]: (res) => res.status === 200,
        [`${endpoint}: success envelope is valid`]: () => isSuccessEnvelope(body),
        [`${endpoint}: response data shape is valid`]: () => isSuccessEnvelope(body) && validateData(body.data),
    }, tags);

    return { passed, body };
}

export function isMemberData(data) {
    return data !== null
        && typeof data === 'object'
        && Number.isInteger(data.memberId)
        && typeof data.email === 'string'
        && data.email.length > 0
        && typeof data.memberStatus === 'string';
}

export function isUserCardListData(data) {
    return data !== null
        && typeof data === 'object'
        && Array.isArray(data.userCards)
        && Number.isInteger(data.totalCount)
        && data.totalCount === data.userCards.length
        && data.userCards.every((card) => Number.isInteger(card.userCardId));
}

export function isUserCardDetailData(data) {
    return data !== null
        && typeof data === 'object'
        && Number.isInteger(data.userCardId)
        && Number.isInteger(data.cardId)
        && typeof data.cardName === 'string';
}

export function isNotificationListData(data) {
    return data !== null
        && typeof data === 'object'
        && Array.isArray(data.notifications)
        && Number.isInteger(data.totalCount)
        && data.totalCount === data.notifications.length
        && typeof data.hasNext === 'boolean'
        && data.notifications.every((notification) => Number.isInteger(notification.notificationId));
}
