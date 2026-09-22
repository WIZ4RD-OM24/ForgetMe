// Load test: how many deletion requests can be filed and confirmed per second?
// Each iteration does what a real person does: file a request, read the code from the inbox, confirm it.
//
//   docker compose --profile demo up -d --build
//   docker run --rm -i --network java-project_default grafana/k6 run - \
//     -e BASE=http://orchestrator:8080 -e MAIL=http://mailpit:8025 < loadtest/file-and-verify.js
//
// Afterwards, the dispatcher keeps working through the requests; measure that part with the SQL in docs/phase-5.md.

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE || 'http://localhost:8080';
const MAIL = __ENV.MAIL || 'http://localhost:8025';

export const options = {
    scenarios: {
        people: { executor: 'shared-iterations', vus: Number(__ENV.VUS || 20), iterations: Number(__ENV.ITERATIONS || 200), maxDuration: '5m' },
    },
    thresholds: {
        'http_req_failed{name:file}': ['rate<0.01'],
        'http_req_failed{name:verify}': ['rate<0.01'],
        'http_req_duration{name:file}': ['p(95)<1000'],
        'http_req_duration{name:verify}': ['p(95)<1000'],
    },
};

const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };

export default function () {
    const email = `load-${__VU}-${__ITER}-${Date.now()}@example.com`;

    const filed = http.post(`${BASE}/api/requests`, JSON.stringify({ email }), { ...JSON_HEADERS, tags: { name: 'file' } });
    if (!check(filed, { 'filed': r => r.status === 201 })) return;

    let code = null;
    for (let i = 0; i < 25 && !code; i++) {
        const found = http.get(`${MAIL}/api/v1/search?query=${encodeURIComponent('to:' + email)}`, { tags: { name: 'inbox' } });
        const messages = found.json('messages') || [];
        if (messages.length) code = /code is (\d{6})/.exec(messages[0].Snippet)[1];
        else sleep(0.2);
    }
    if (!check(code, { 'code arrived by email': c => c !== null })) return;

    const verified = http.post(`${BASE}/api/requests/${filed.json('id')}/verify`,
        JSON.stringify({ code }), { ...JSON_HEADERS, tags: { name: 'verify' } });
    check(verified, { 'confirmed': r => r.status === 200 });
}
