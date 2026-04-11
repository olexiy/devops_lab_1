const BASE = '/api/v1'

async function request(path) {
  const res = await fetch(`${BASE}${path}`)
  if (!isOk(res)) {
    const text = await res.text().catch(() => '')
    const err = new Error(text || `HTTP ${res.status}`)
    err.status = res.status
    throw err
  }
  return res.json()
}

function isOk(res) {
  return res.status >= 200 && res.status < 300
}

// ── Customers ──────────────────────────────────────────────────────────────

export function fetchCustomers({ page = 0, size = 20, lastName = '', status = '' } = {}) {
  const params = new URLSearchParams({ page, size })
  if (lastName) params.set('lastName', lastName)
  if (status)   params.set('status', status)
  return request(`/customers?${params}`)
}

export function fetchCustomer(id) {
  return request(`/customers/${id}`)
}

// ── Accounts ───────────────────────────────────────────────────────────────

export function fetchAccountsByCustomer(customerId, { page = 0, size = 20 } = {}) {
  const params = new URLSearchParams({ page, size })
  return request(`/accounts/customer/${customerId}?${params}`)
}

export function fetchAccount(id) {
  return request(`/accounts/${id}`)
}

// ── Transactions ───────────────────────────────────────────────────────────

export function fetchTransactionsByAccount(accountId, { page = 0, size = 20 } = {}) {
  const params = new URLSearchParams({ page, size })
  return request(`/transactions/account/${accountId}?${params}`)
}

// ── Ratings ────────────────────────────────────────────────────────────────

// Returns null when rating does not exist (404); throws on other errors.
export async function fetchRating(customerId) {
  const res = await fetch(`${BASE}/ratings/${customerId}`)
  if (res.status === 404) return null
  if (!isOk(res)) {
    const text = await res.text().catch(() => '')
    const err = new Error(text || `HTTP ${res.status}`)
    err.status = res.status
    throw err
  }
  return res.json()
}
