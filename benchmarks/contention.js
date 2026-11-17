import http from 'k6/http';
import encoding from 'k6/encoding';
import {Counter} from 'k6/metrics';

const winners = new Counter('hold_success');
const conflicts = new Counter('hold_conflict');
export const options = {vus: Number(__ENV.VUS || 100), iterations: Number(__ENV.VUS || 100), maxDuration: '5m'};
export default function () {
    const user = 'load-' + __VU + '-' + (__ENV.RUN_ID || 'one');
    const pass = 'load-password-123';
    const base = __ENV.BASE_URL || 'http://localhost:8086';
    http.post(base + '/api/v1/register', JSON.stringify({
        username: user,
        password: pass
    }), {headers: {'Content-Type': 'application/json'}});
    const r = http.post(base + '/api/v1/holds', JSON.stringify({
        occurrenceId: __ENV.OCCURRENCE_ID,
        seatIds: [__ENV.SEAT_ID]
    }), {
        headers: {
            Authorization: 'Basic ' + encoding.b64encode(user + ':' + pass),
            'Content-Type': 'application/json',
            'Idempotency-Key': 'load-' + __VU
        }
    });
    if (r.status === 200) winners.add(1);
    if (r.status === 409) conflicts.add(1);
}
