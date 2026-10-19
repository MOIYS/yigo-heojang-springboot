const DEFAULT_BASE_URL = 'http://localhost:8080';
const DEFAULT_ACCOUNT_COUNT = 20;
const WARMUP_SECONDS = 60;
const COOLDOWN_SECONDS = 30;

function positiveInteger(name, fallback) {
    const raw = __ENV[name];
    if (raw === undefined || raw === '') {
        return fallback;
    }

    const value = Number(raw);
    if (!Number.isInteger(value) || value < 1) {
        throw new Error(`${name} must be a positive integer; received "${raw}"`);
    }
    return value;
}

function durationSeconds(name, fallback) {
    const raw = __ENV[name] || fallback;
    const match = /^(\d+(?:\.\d+)?)(ms|s|m|h)$/.exec(raw);
    if (!match) {
        throw new Error(`${name} must use one k6 duration unit (for example 300s or 5m); received "${raw}"`);
    }

    const multipliers = { ms: 0.001, s: 1, m: 60, h: 3600 };
    const seconds = Number(match[1]) * multipliers[match[2]];
    if (seconds <= 0) {
        throw new Error(`${name} must be greater than zero`);
    }
    return seconds;
}

function endpointThresholds(endpoint) {
    const selector = `phase:baseline,endpoint:${endpoint}`;
    return {
        [`checks{${selector}}`]: ['rate==1'],
        [`http_req_failed{${selector}}`]: ['rate<0.01'],
        [`http_req_duration{${selector}}`]: ['p(50)>=0', 'p(95)>=0', 'p(99)>=0'],
        [`http_reqs{${selector}}`]: ['rate>=0'],
    };
}

export const BASE_URL = (__ENV.BASE_URL || DEFAULT_BASE_URL).replace(/\/+$/, '');
export const PERF_PASSWORD = __ENV.PERF_PASSWORD || '';
export const PERF_ACCOUNT_COUNT = positiveInteger('PERF_ACCOUNT_COUNT', DEFAULT_ACCOUNT_COUNT);
export const BASELINE_VUS = positiveInteger('BASELINE_VUS', 10);
export const BASELINE_DURATION_SECONDS = durationSeconds('BASELINE_DURATION', '5m');
export const TOTAL_PROFILE_SECONDS = WARMUP_SECONDS + BASELINE_DURATION_SECONDS + COOLDOWN_SECONDS;

if (TOTAL_PROFILE_SECONDS >= 600) {
    throw new Error(
        `The configured profile is ${TOTAL_PROFILE_SECONDS}s; it must remain below the 600s access-token lifetime`,
    );
}

const endpoints = [
    'members_me',
    'user_cards_list',
    'user_cards_detail',
    'notifications_list',
];

const thresholds = {
    'checks{phase:baseline}': ['rate==1'],
    'http_req_failed{phase:baseline}': ['rate<0.01'],
};

for (const endpoint of endpoints) {
    Object.assign(thresholds, endpointThresholds(endpoint));
}

export const authenticatedReadOptions = {
    setupTimeout: '2m',
    summaryTrendStats: ['min', 'avg', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
    thresholds,
    scenarios: {
        warmup: {
            executor: 'ramping-vus',
            exec: 'warmup',
            startVUs: 0,
            stages: [{ duration: `${WARMUP_SECONDS}s`, target: BASELINE_VUS }],
            gracefulRampDown: '0s',
            gracefulStop: '0s',
            tags: { phase: 'warmup' },
        },
        baseline: {
            executor: 'constant-vus',
            exec: 'baseline',
            vus: BASELINE_VUS,
            duration: `${BASELINE_DURATION_SECONDS}s`,
            startTime: `${WARMUP_SECONDS}s`,
            gracefulStop: '0s',
            tags: { phase: 'baseline' },
        },
        cooldown: {
            executor: 'ramping-vus',
            exec: 'cooldown',
            startVUs: BASELINE_VUS,
            stages: [{ duration: `${COOLDOWN_SECONDS}s`, target: 0 }],
            startTime: `${WARMUP_SECONDS + BASELINE_DURATION_SECONDS}s`,
            gracefulRampDown: '0s',
            gracefulStop: '0s',
            tags: { phase: 'cooldown' },
        },
    },
};

export const smokeOptions = {
    vus: 1,
    iterations: 1,
    setupTimeout: '30s',
    summaryTrendStats: ['min', 'avg', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
    thresholds: {
        checks: ['rate==1'],
        http_req_failed: ['rate==0'],
    },
};

export function requirePerfPassword() {
    if (!PERF_PASSWORD) {
        throw new Error('PERF_PASSWORD is required and must be supplied through the environment');
    }
    return PERF_PASSWORD;
}
