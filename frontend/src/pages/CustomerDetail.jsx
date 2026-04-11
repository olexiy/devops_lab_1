import { useState, useEffect, useCallback } from 'react'
import { useParams, Link } from 'react-router-dom'
import { fetchCustomer, fetchAccountsByCustomer } from '../api.js'
import StatusBadge from '../components/StatusBadge.jsx'
import Pagination from '../components/Pagination.jsx'
import LoadingSpinner from '../components/LoadingSpinner.jsx'
import ErrorMessage from '../components/ErrorMessage.jsx'

const PAGE_SIZE = 20

function formatDate(str) {
  if (!str) return '—'
  return new Date(str).toLocaleDateString()
}

function formatCurrency(amount, currency = 'EUR') {
  return new Intl.NumberFormat('de-DE', { style: 'currency', currency }).format(amount)
}

export default function CustomerDetail() {
  const { id } = useParams()

  const [customer,    setCustomer]    = useState(null)
  const [custLoading, setCustLoading] = useState(true)
  const [custError,   setCustError]   = useState(null)

  const [accounts,   setAccounts]   = useState(null)
  const [accPage,    setAccPage]    = useState(0)
  const [accLoading, setAccLoading] = useState(true)
  const [accError,   setAccError]   = useState(null)

  useEffect(() => {
    setCustLoading(true)
    setCustError(null)
    fetchCustomer(id)
      .then(setCustomer)
      .catch(setCustError)
      .finally(() => setCustLoading(false))
  }, [id])

  const loadAccounts = useCallback(() => {
    setAccLoading(true)
    setAccError(null)
    fetchAccountsByCustomer(id, { page: accPage, size: PAGE_SIZE })
      .then(setAccounts)
      .catch(setAccError)
      .finally(() => setAccLoading(false))
  }, [id, accPage])

  useEffect(() => { loadAccounts() }, [loadAccounts])

  return (
    <div>
      <nav className="text-sm text-gray-500 mb-4">
        <Link to="/customers" className="hover:text-blue-600">Customers</Link>
        <span className="mx-2">/</span>
        <span className="text-gray-800">
          {customer ? `${customer.firstName} ${customer.lastName}` : `Customer #${id}`}
        </span>
      </nav>

      {custLoading && <LoadingSpinner message="Loading customer…" />}
      {custError   && <ErrorMessage error={custError} />}
      {customer    && (
        <div className="bg-white rounded-lg shadow-sm border border-gray-200 p-6 mb-8">
          <div className="flex items-start justify-between mb-4">
            <h1 className="text-2xl font-semibold text-gray-900">
              {customer.firstName} {customer.lastName}
            </h1>
            <StatusBadge value={customer.status} />
          </div>
          <dl className="grid grid-cols-2 gap-x-8 gap-y-3 text-sm">
            <div>
              <dt className="text-gray-500">Email</dt>
              <dd className="text-gray-900 mt-0.5">{customer.email}</dd>
            </div>
            <div>
              <dt className="text-gray-500">Phone</dt>
              <dd className="text-gray-900 mt-0.5">{customer.phone || '—'}</dd>
            </div>
            <div>
              <dt className="text-gray-500">Date of birth</dt>
              <dd className="text-gray-900 mt-0.5">{formatDate(customer.dateOfBirth)}</dd>
            </div>
            <div>
              <dt className="text-gray-500">Member since</dt>
              <dd className="text-gray-900 mt-0.5">{formatDate(customer.createdAt)}</dd>
            </div>
          </dl>
        </div>
      )}

      <h2 className="text-lg font-semibold text-gray-800 mb-4">Accounts</h2>

      {accLoading && <LoadingSpinner message="Loading accounts…" />}
      {accError   && <ErrorMessage error={accError} onRetry={loadAccounts} />}
      {!accLoading && !accError && accounts && (
        <>
          <div className="bg-white rounded-lg shadow-sm border border-gray-200 overflow-hidden">
            <table className="min-w-full divide-y divide-gray-200">
              <thead className="bg-gray-50">
                <tr>
                  {['Account Number', 'Type', 'Balance', 'Status', 'Opened', ''].map(h => (
                    <th key={h}
                        className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {accounts.content.length === 0 ? (
                  <tr>
                    <td colSpan={6} className="text-center py-10 text-gray-500 text-sm">
                      No accounts found.
                    </td>
                  </tr>
                ) : accounts.content.map(a => (
                  <tr key={a.id} className="hover:bg-gray-50">
                    <td className="px-4 py-3 text-sm font-mono text-gray-900">{a.accountNumber}</td>
                    <td className="px-4 py-3 text-sm text-gray-600">{a.accountType}</td>
                    <td className="px-4 py-3 text-sm font-medium text-gray-900">
                      {formatCurrency(a.balance, a.currency)}
                    </td>
                    <td className="px-4 py-3"><StatusBadge value={a.status} /></td>
                    <td className="px-4 py-3 text-sm text-gray-500">{formatDate(a.createdAt)}</td>
                    <td className="px-4 py-3 text-right">
                      <Link to={`/accounts/${a.id}`} className="text-sm text-blue-600 hover:underline">
                        View
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination
            page={accounts.page.number}
            totalPages={accounts.page.totalPages}
            onPageChange={setAccPage}
          />
        </>
      )}
    </div>
  )
}
