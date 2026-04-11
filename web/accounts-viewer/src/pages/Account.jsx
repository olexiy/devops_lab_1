import { useState, useEffect, useCallback } from 'react'
import { useParams, Link } from 'react-router-dom'
import { fetchAccount, fetchRating, fetchTransactionsByAccount } from '../api.js'
import Badge from '../components/Badge.jsx'
import Spinner from '../components/Spinner.jsx'
import Alert from '../components/Alert.jsx'
import Pager from '../components/Pager.jsx'

const PAGE_SIZE = 25

function fmt(str)         { return str ? new Date(str).toLocaleDateString() : '—' }
function fmtDt(str)       { return str ? new Date(str).toLocaleString() : '—' }
function currency(v, cur) { return new Intl.NumberFormat('de-DE', { style: 'currency', currency: cur ?? 'EUR' }).format(v) }

function StatCard({ label, value, sub }) {
  return (
    <div className="bg-white rounded-xl border border-slate-200 shadow-sm p-5">
      <p className="text-xs font-semibold text-slate-500 uppercase tracking-wide mb-1">{label}</p>
      <p className="text-xl font-bold text-slate-900 leading-tight">{value}</p>
      {sub && <p className="text-xs text-slate-400 mt-0.5">{sub}</p>}
    </div>
  )
}

// ── Rating section ─────────────────────────────────────────────────────────

function RISK_COLOR(level) {
  return { LOW: 'text-emerald-600', MEDIUM: 'text-amber-600', HIGH: 'text-orange-600', CRITICAL: 'text-red-600' }[level] ?? 'text-slate-600'
}

function RatingPanel({ customerId }) {
  const [rating,  setRating]  = useState(undefined)
  const [loading, setLoading] = useState(true)
  const [error,   setError]   = useState(null)

  useEffect(() => {
    setLoading(true)
    setError(null)
    fetchRating(customerId)
      .then(setRating)
      .catch(setError)
      .finally(() => setLoading(false))
  }, [customerId])

  if (loading) return <Spinner message="Loading rating…" />
  if (error)   return <Alert error={error} />

  if (rating === null) {
    return (
      <div className="bg-white rounded-xl border border-slate-200 shadow-sm p-6 mb-6">
        <h2 className="text-base font-semibold text-slate-800 mb-2">Credit Rating</h2>
        <p className="text-sm text-slate-400">No rating calculated yet for this customer.</p>
      </div>
    )
  }

  // Score bar: assume 0–1000
  const pct = Math.min(100, Math.max(0, (rating.ratingScore / 1000) * 100))

  return (
    <div className="bg-white rounded-xl border border-slate-200 shadow-sm p-6 mb-6">
      <div className="flex items-start justify-between mb-4">
        <h2 className="text-base font-semibold text-slate-800">Credit Rating</h2>
        <div className="flex items-center gap-3">
          <span className={`text-3xl font-black ${RISK_COLOR(rating.riskLevel)}`}>
            {rating.ratingClass}
          </span>
          <Badge value={rating.riskLevel} />
        </div>
      </div>

      {/* Score bar */}
      <div className="mb-5">
        <div className="flex justify-between text-xs text-slate-500 mb-1">
          <span>Score</span>
          <span className="font-semibold text-slate-700">{rating.ratingScore}</span>
        </div>
        <div className="h-2 bg-slate-100 rounded-full overflow-hidden">
          <div
            className="h-full bg-indigo-500 rounded-full transition-all"
            style={{ width: `${pct}%` }}
          />
        </div>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-sm">
        <div>
          <p className="text-xs text-slate-500 mb-0.5">Avg. Balance 12m</p>
          <p className="font-semibold text-slate-900">{currency(rating.avgBalance12m)}</p>
        </div>
        <div>
          <p className="text-xs text-slate-500 mb-0.5">Transaction Vol. 12m</p>
          <p className="font-semibold text-slate-900">{currency(rating.transactionVolume12m)}</p>
        </div>
        <div>
          <p className="text-xs text-slate-500 mb-0.5">Products</p>
          <p className="font-semibold text-slate-900">{rating.productCount}</p>
        </div>
        <div>
          <p className="text-xs text-slate-500 mb-0.5">Calculated at</p>
          <p className="font-semibold text-slate-900">{fmtDt(rating.calculatedAt)}</p>
        </div>
      </div>
    </div>
  )
}

// ── Main page ──────────────────────────────────────────────────────────────

export default function Account() {
  const { id } = useParams()

  const [account,    setAccount]    = useState(null)
  const [accLoading, setAccLoading] = useState(true)
  const [accError,   setAccError]   = useState(null)

  const [transactions, setTransactions] = useState(null)
  const [txPage,       setTxPage]       = useState(0)
  const [txLoading,    setTxLoading]    = useState(true)
  const [txError,      setTxError]      = useState(null)

  useEffect(() => {
    setAccLoading(true)
    setAccError(null)
    fetchAccount(id)
      .then(setAccount)
      .catch(setAccError)
      .finally(() => setAccLoading(false))
  }, [id])

  const loadTx = useCallback(() => {
    setTxLoading(true)
    setTxError(null)
    fetchTransactionsByAccount(id, { page: txPage, size: PAGE_SIZE })
      .then(setTransactions)
      .catch(setTxError)
      .finally(() => setTxLoading(false))
  }, [id, txPage])

  useEffect(() => { loadTx() }, [loadTx])

  return (
    <div>
      {/* Breadcrumb */}
      <nav className="flex items-center gap-1.5 text-sm text-slate-500 mb-6">
        <Link to="/customers" className="hover:text-indigo-600 transition-colors">Customers</Link>
        <svg className="w-4 h-4 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
        </svg>
        {account && (
          <>
            <Link to={`/customers/${account.customerId}`} className="hover:text-indigo-600 transition-colors">
              Customer #{account.customerId}
            </Link>
            <svg className="w-4 h-4 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
            </svg>
          </>
        )}
        <span className="text-slate-800 font-medium font-mono">
          {account?.accountNumber ?? `Account #${id}`}
        </span>
      </nav>

      {accLoading && <Spinner message="Loading account…" />}
      {accError   && <Alert error={accError} />}

      {account && (
        <>
          {/* Account header card */}
          <div className="bg-white rounded-xl border border-slate-200 shadow-sm p-6 mb-5">
            <div className="flex items-start justify-between mb-4">
              <div>
                <p className="text-xs font-semibold text-slate-500 uppercase tracking-wide mb-1">
                  {account.accountType}
                </p>
                <h1 className="text-2xl font-bold text-slate-900 font-mono">
                  {account.accountNumber}
                </h1>
              </div>
              <Badge value={account.status} />
            </div>
            {/* Stat row */}
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4 pt-4 border-t border-slate-100">
              <div>
                <p className="text-xs text-slate-500 mb-0.5">Balance</p>
                <p className="text-xl font-black text-slate-900">
                  {currency(account.balance, account.currency)}
                </p>
              </div>
              <div>
                <p className="text-xs text-slate-500 mb-0.5">Currency</p>
                <p className="text-sm font-semibold text-slate-900">{account.currency}</p>
              </div>
              <div>
                <p className="text-xs text-slate-500 mb-0.5">Opened</p>
                <p className="text-sm font-semibold text-slate-900">{fmt(account.createdAt)}</p>
              </div>
              <div>
                <p className="text-xs text-slate-500 mb-0.5">Account ID</p>
                <p className="text-sm font-mono text-slate-500">{account.id}</p>
              </div>
            </div>
          </div>

          {/* Rating */}
          <RatingPanel customerId={account.customerId} />
        </>
      )}

      {/* Transactions */}
      <div className="flex items-baseline justify-between mb-4">
        <h2 className="text-lg font-semibold text-slate-800">Transactions</h2>
        {transactions && (
          <span className="text-sm text-slate-500">
            {transactions.page.totalElements.toLocaleString()} total
          </span>
        )}
      </div>

      {txLoading && <Spinner message="Loading transactions…" />}
      {txError   && <Alert error={txError} onRetry={loadTx} />}

      {!txLoading && !txError && transactions && (
        <>
          <div className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden">
            <table className="min-w-full">
              <thead>
                <tr className="border-b border-slate-200 bg-slate-50">
                  {['Date', 'Reference', 'Type', 'Description', 'Amount', 'Balance after'].map(h => (
                    <th key={h}
                        className="px-5 py-3 text-left text-xs font-semibold text-slate-500 uppercase tracking-wider">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {transactions.content.length === 0 ? (
                  <tr>
                    <td colSpan={6} className="text-center py-16 text-slate-400 text-sm">
                      No transactions found.
                    </td>
                  </tr>
                ) : transactions.content.map(tx => {
                  const isIn = tx.transactionType === 'CREDIT' || tx.transactionType === 'TRANSFER_IN'
                  return (
                    <tr key={tx.id} className="hover:bg-slate-50 transition-colors">
                      <td className="px-5 py-3.5 text-sm text-slate-500 whitespace-nowrap">
                        {fmtDt(tx.transactionDate)}
                      </td>
                      <td className="px-5 py-3.5 text-xs font-mono text-slate-400 max-w-28 truncate">
                        {tx.referenceNumber}
                      </td>
                      <td className="px-5 py-3.5">
                        <Badge value={tx.transactionType} />
                      </td>
                      <td className="px-5 py-3.5 text-sm text-slate-600 max-w-xs truncate">
                        {tx.description || '—'}
                      </td>
                      <td className={`px-5 py-3.5 text-sm font-semibold tabular-nums
                        ${isIn ? 'text-emerald-600' : 'text-red-600'}`}>
                        {isIn ? '+' : '−'}{currency(tx.amount, tx.currency)}
                      </td>
                      <td className="px-5 py-3.5 text-sm text-slate-500 tabular-nums">
                        {currency(tx.balanceAfter, tx.currency)}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
          <Pager
            page={transactions.page.number}
            totalPages={transactions.page.totalPages}
            onPageChange={setTxPage}
          />
        </>
      )}
    </div>
  )
}
