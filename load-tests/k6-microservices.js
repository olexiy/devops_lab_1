/**
 * k6 load test — Microservices stack
 *
 * Profile:
 *   Light   0:00 – 10:00   15 VUs  (normal traffic)
 *   Medium 10:00 – 13:00   60 VUs  (peak load)
 *   Light  13:00 – 18:00   15 VUs  (cool-down)
 *   Ramp   18:00 – 19:00    0 VUs
 *
 * Prerequisites:
 *   1. Docker Compose services running:  cd services && docker compose up -d
 *   2. Gateway running locally:          cd services/infra/gateway && java -jar target/gateway-*.jar
 *   3. (Optional) Data loaded:           cd data-generator && python main.py
 *
 * Usage:
 *   k6 run load-tests/k6-microservices.js
 *   k6 run --env BASE_URL=http://localhost:8080 load-tests/k6-microservices.js
 */

import http from 'k6/http';
import { sleep, check, group } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 4xx responses are expected (missing ratings, non-existent IDs) — not real failures.
// Only 5xx and network errors count against the failure threshold.
const softParams = {
  responseCallback: http.expectedStatuses({ min: 200, max: 499 }),
};
const jsonPostParams = {
  headers: { 'Content-Type': 'application/json' },
  responseCallback: http.expectedStatuses({ min: 200, max: 499 }),
};

export const options = {
  stages: [
    // --- Light phase: 10 minutes ---
    { duration: '1m', target: 15 },   // ramp up
    { duration: '8m', target: 15 },   // sustain
    { duration: '1m', target: 60 },   // transition to medium
    // --- Medium phase: 3 minutes ---
    { duration: '2m', target: 60 },   // sustain
    { duration: '1m', target: 15 },   // transition back to light
    // --- Light cool-down: 5 minutes ---
    { duration: '4m', target: 15 },   // sustain
    { duration: '1m', target: 0 },    // ramp down
  ],
  thresholds: {
    http_req_duration: ['p(95)<500'],   // 95th percentile response time under 500 ms
    http_req_failed:   ['rate<0.01'],   // server errors (5xx) under 1%
  },
};

/**
 * Runs once before VUs start. Fetches real customer and account IDs from the API
 * so the load test operates on actual data. Falls back to ID range 1-10 if the DB
 * is empty (run the data generator first for meaningful results).
 */
export function setup() {
  const customersRes = http.get(`${BASE_URL}/api/v1/customers?page=0&size=50`);
  let customerIds = [];
  if (customersRes.status === 200) {
    try {
      const body = JSON.parse(customersRes.body);
      customerIds = (body.content || []).map(c => c.id);
    } catch (_) {}
  }

  const accountsRes = http.get(`${BASE_URL}/api/v1/accounts?page=0&size=50`);
  let accountIds = [];
  if (accountsRes.status === 200) {
    try {
      const body = JSON.parse(accountsRes.body);
      accountIds = (body.content || []).map(a => a.id);
    } catch (_) {}
  }

  if (customerIds.length === 0) {
    console.warn('No customers found — using fallback IDs 1-10. Run the data generator for real data.');
    customerIds = Array.from({ length: 10 }, (_, i) => i + 1);
  }
  if (accountIds.length === 0) {
    console.warn('No accounts found — using fallback IDs 1-10.');
    accountIds = Array.from({ length: 10 }, (_, i) => i + 1);
  }

  console.log(`setup complete: ${customerIds.length} customers, ${accountIds.length} accounts`);
  return { customerIds, accountIds };
}

function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

/**
 * VU function — runs in a loop for the duration of the test.
 *
 * Weighted mix:
 *   40% — browse customer profile + their accounts  (gateway → customer-service + account-service)
 *   30% — list transactions for an account          (gateway → transaction-service, no Feign calls)
 *   20% — rating lookup                             (gateway → rating-service → PostgreSQL)
 *   10% — create a transaction (write path)         (gateway → transaction-service → account-service x2)
 */
export default function (data) {
  const customerId = pick(data.customerIds);
  const accountId  = pick(data.accountIds);
  const roll       = Math.random();

  if (roll < 0.40) {
    // Customer profile read — exercises customer-service then account-service
    group('customer_profile', () => {
      check(
        http.get(`${BASE_URL}/api/v1/customers/${customerId}`, softParams),
        { 'customer ok': r => r.status < 500 },
      );
      sleep(0.2);
      check(
        http.get(`${BASE_URL}/api/v1/accounts/customer/${customerId}?page=0&size=10`, softParams),
        { 'accounts ok': r => r.status < 500 },
      );
    });

  } else if (roll < 0.70) {
    // Transaction history — read-only, single service, no inter-service calls
    group('transaction_history', () => {
      check(
        http.get(`${BASE_URL}/api/v1/transactions/account/${accountId}?page=0&size=20`, softParams),
        { 'transactions ok': r => r.status < 500 },
      );
    });

  } else if (roll < 0.90) {
    // Rating lookup — hits PostgreSQL via rating-service
    group('rating_lookup', () => {
      check(
        http.get(`${BASE_URL}/api/v1/ratings/${customerId}`, softParams),
        { 'rating ok': r => r.status < 500 },
      );
    });

  } else {
    // Transaction write — most expensive path:
    //   transaction-service validates account (Feign GET) then updates balance (Feign PATCH)
    group('create_transaction', () => {
      const payload = JSON.stringify({
        accountId:       accountId,
        amount:          parseFloat((Math.random() * 99 + 1).toFixed(2)),
        transactionType: 'CREDIT',
      });
      check(
        http.post(`${BASE_URL}/api/v1/transactions`, payload, jsonPostParams),
        { 'transaction accepted': r => r.status === 201 || r.status === 422 },
      );
    });
  }

  // Think time: 100–500 ms between iterations (keeps load realistic)
  sleep(Math.random() * 0.4 + 0.1);
}
