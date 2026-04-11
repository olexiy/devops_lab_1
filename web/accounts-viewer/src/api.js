const BASE = '/api/v1'

async function get(path) {
  const res = await fetch(`${BASE}${path}`)
  if (res.status >= 200 && res.status < 300) return res.json()
  const text = await res.text().catch(() => '')
  const err = new Error(text || `HTTP ${res.status}`)
  err.status = res.status
  throw err
}

// ── Customers ──────────────────────────────────────────────────────────────

export function fetchCustomers({ page = 0, size = 20, lastName = '', status = '' } = {}) {
  const p = new URLSearchParams({ page, size })
  if (lastName) p.set('lastName', lastName)
  if (status)   p.set('status', status)
  return get(`/customers?${p}`)
}

export function fetchCustomer(id) {
  return get(`/customers/${id}`)
}

// ── Accounts ───────────────────────────────────────────────────────────────

export function fetchAccountsByCustomer(customerId, { page = 0, size = 20 } = {}) {
  return get(`/accounts/customer/${customerId}?${new URLSearchParams({ page, size })}`)
}

export function fetchAccount(id) {
  return get(`/accounts/${id}`)
}

// ── Transactions ───────────────────────────────────────────────────────────

export function fetchTransactionsByAccount(accountId, { page = 0, size = 25 } = {}) {
  return get(`/transactions/account/${accountId}?${new URLSearchParams({ page, size })}`)
}

// ── Ratings ────────────────────────────────────────────────────────────────

export async function fetchRating(customerId) {
  const res = await fetch(`${BASE}/ratings/${customerId}`)
  if (res.status === 404) return null
  if (res.status >= 200 && res.status < 300) return res.json()
  const text = await res.text().catch(() => '')
  const err = new Error(text || `HTTP ${res.status}`)
  err.status = res.status
  throw err
}
