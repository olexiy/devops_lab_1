import { useState, useEffect, useCallback } from 'react'
import { useParams, Link } from 'react-router-dom'
import { fetchAccount, fetchRating, fetchTransactionsByAccount } from '../api.js'
import StatusBadge from '../components/StatusBadge.jsx'
import Pagination from '../components/Pagination.jsx'
import LoadingSpinner from '../components/LoadingSpinner.jsx'
import ErrorMessage from '../components/ErrorMessage.jsx'

const PAGE_SIZE = 20

function formatDate(str) {
  if (!str) return '—'
  return new Date(str).toLocaleDateString()
}

function formatDateTime(str) {
  if (!str) return '—'
  return new Date(str).toLocaleString()
}

function formatCurrency(amount, currency = 'EUR') {
  return new Intl.NumberFormat('de-DE', { style: 'currency', currency }).format(amount)
}

// ── Rating Card ─────────────────────────────────────────────────────────────

function RatingCard({ customerId }) {
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

  if (loading) return <LoadingSpinner message="Loading rating…" />
  if (error)   return <ErrorMessage error={error} />

  if (rating === null) {
    return (
      <div className="bg-white rounded-lg shadow-sm border border-gray-200 p-6 mb-8">
        <h2 className="text-lg font-semibold text-gray-800 mb-2">Rating</h2>
        <p className="text-sm text-gray-500">No rating has been calculated for this customer yet.</p>
      </div>
    )
  }

  return (
    <div className="bg-white rounded-lg shadow-sm border border-gray-200 p-6 mb-8">
      <div className="flex items-start justify-between mb-4">
        <h2 className="text-lg font-semibold text-gray-800">Rating</h2>
        <div className="flex items-center gap-3">
          <span className="text-3xl font-bold text-blue-700">{rating.ratingScore}</span>
          <span className="text-xl font-semibold text-gray-700">{rating.ratingClass}</span>
          <StatusBadge value={rating.riskLevel} />
        </div>
      </div>
      <dl className="grid grid-cols-3 gap-x-8 gap-y-3 text-sm">
        <div>
          <dt className="text-gray-500">Avg. Balance (12m)</dt>
          <dd className="text-gray-900 mt-0.5 font-medium">{formatCurrency(rating.avgBalance12m)}</dd>
        </div>
        <div>
          <dt className="text-gray-500">Transaction Volume (12m)</dt>
          <dd className="text-gray-900 mt-0.5 font-medium">{formatCurrency(rating.transactionVolume12m)}</dd>
        </div>
        <div>
          <dt className="text-gray-500">Products</dt>
          <dd className="text-gray-900 mt-0.5">{rating.productCount}</dd>
        </div>
        <div>
          <dt className="text-gray-500">Calculated at</dt>
          <dd className="text-gray-900 mt-0.5">{formatDateTime(rating.calculatedAt)}</dd>
        </div>
      </dl>
    </div>
  )
}

// ── Main Page ────────────────────────────────────────────────────────────────

export default function AccountDetail() {
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

  const loadTransactions = useCallback(() => {
    setTxLoading(true)
    setTxError(null)
    fetchTransactionsByAccount(id, { page: txPage, size: PAGE_SIZE })
      .then(setTransactions)
      .catch(setTxError)
      .finally(() => setTxLoading(false))
  }, [id, txPage])

  useEffect(() => { loadTransactions() }, [loadTransactions])

  return (
    <div>
      <nav className="text-sm text-gray-500 mb-4">
        <Link to="/customers" className="hover:text-blue-600">Customers</Link>
        {account && (
          <>
            <span className="mx-2">/</span>
            <Link to={`/customers/${account.customerId}`} className="hover:text-blue-600">
              Customer #{account.customerId}
            </Link>
          </>
        )}
        <span className="mx-2">/</span>
        <span className="text-gray-800">
          {account ? account.accountNumber : `Account #${id}`}
        </span>
      </nav>

      {accLoading && <LoadingSpinner message="Loading account…" />}
      {accError   && <ErrorMessage error={accError} />}
      {account    && (
        <div className="bg-white rounded-lg shadow-sm border border-gray-200 p-6 mb-8">
          <div className="flex items-start justify-between mb-4">
            <div>
              <h1 className="text-2xl font-semibold text-gray-900 font-mono">
                {account.accountNumber}
              </h1>
              <p className="text-gray-500 text-sm mt-0.5">{account.accountType}</p>
            </div>
            <StatusBadge value={account.status} />
          </div>
          <dl className="grid grid-cols-2 gap-x-8 gap-y-3 text-sm">
            <div>
              <dt className="text-gray-500">Balance</dt>
              <dd className="text-gray-900 mt-0.5 text-xl font-bold">
                {formatCurrency(account.balance, account.currency)}
              </dd>
            </div>
            <div>
              <dt className="text-gray-500">Currency</dt>
              <dd className="text-gray-900 mt-0.5">{account.currency}</dd>
            </div>
            <div>
              <dt className="text-gray-500">Opened</dt>
              <dd className="text-gray-900 mt-0.5">{formatDate(account.createdAt)}</dd>
            </div>
            <div>
              <dt className="text-gray-500">Account ID</dt>
              <dd className="text-gray-900 mt-0.5 font-mono text-gray-600">{account.id}</dd>
            </div>
          </dl>
        </div>
      )}

      {account && <RatingCard customerId={account.customerId} />}

      <h2 className="text-lg font-semibold text-gray-800 mb-4">Transactions</h2>

      {txLoading && <LoadingSpinner message="Loading transactions…" />}
      {txError   && <ErrorMessage error={txError} onRetry={loadTransactions} />}
      {!txLoading && !txError && transactions && (
        <>
          <div className="bg-white rounded-lg shadow-sm border border-gray-200 overflow-hidden">
            <table className="min-w-full divide-y divide-gray-200">
              <thead className="bg-gray-50">
                <tr>
                  {['Date', 'Reference', 'Type', 'Description', 'Amount', 'Balance after'].map(h => (
                    <th key={h}
                        className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {transactions.content.length === 0 ? (
                  <tr>
                    <td colSpan={6} className="text-center py-10 text-gray-500 text-sm">
                      No transactions found.
                    </td>
                  </tr>
                ) : transactions.content.map(tx => {
                  const isCredit = tx.transactionType === 'CREDIT' || tx.transactionType === 'TRANSFER_IN'
                  return (
                    <tr key={tx.id} className="hover:bg-gray-50">
                      <td className="px-4 py-3 text-sm text-gray-500 whitespace-nowrap">
                        {formatDateTime(tx.transactionDate)}
                      </td>
                      <td className="px-4 py-3 text-xs font-mono text-gray-500 max-w-28 truncate">
                        {tx.referenceNumber}
                      </td>
                      <td className="px-4 py-3">
                        <StatusBadge value={tx.transactionType} />
                      </td>
                      <td className="px-4 py-3 text-sm text-gray-600 max-w-xs truncate">
                        {tx.description || '—'}
                      </td>
                      <td className={`px-4 py-3 text-sm font-semibold ${isCredit ? 'text-green-700' : 'text-red-700'}`}>
                        {isCredit ? '+' : '-'}{formatCurrency(tx.amount, tx.currency)}
                      </td>
                      <td className="px-4 py-3 text-sm text-gray-600">
                        {formatCurrency(tx.balanceAfter, tx.currency)}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
          <div className="mt-2 text-xs text-gray-500">
            {transactions.page.totalElements.toLocaleString()} total transactions
          </div>
          <Pagination
            page={transactions.page.number}
            totalPages={transactions.page.totalPages}
            onPageChange={setTxPage}
          />
        </>
      )}
    </div>
  )
}
