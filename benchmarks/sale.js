import http from 'k6/http';
import {check} from 'k6';
import encoding from 'k6/encoding';

const base = __ENV.BASE_URL || 'http://localhost:8086';
const auth = {Authorization: 'Basic ' + encoding.b64encode('customer:demo-customer')};
export const options = {
    scenarios: {
        browse: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.RATE || 1000),
            timeUnit: '1s',
            duration: __ENV.DURATION || '30s',
            preAllocatedVUs: 100,
            maxVUs: 1000
        }
    }, thresholds: {http_req_failed: ['rate<0.01']}
};
export default function () {
    const r = http.get(base + '/api/v1/events', {headers: auth});
    check(r, {'browse successful': r => r.status === 200});
}
