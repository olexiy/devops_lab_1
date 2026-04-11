import { useState, useEffect, useCallback } from 'react'
import { useParams, Link } from 'react-router-dom'
import { fetchCustomer, fetchAccountsByCustomer } from '../api.js'
import Badge from '../components/Badge.jsx'
import Spinner from '../components/Spinner.jsx'
import Alert from '../components/Alert.jsx'
import Pager from '../components/Pager.jsx'

const PAGE_SIZE = 20

function fmt(str) {
  if (!str) return '—'
  return new Date(str).toLocaleDateString()
}

function currency(amount, cur = 'EUR') {
  return new Intl.NumberFormat('de-DE', { style: 'currency', currency: cur }).format(amount)
}

function Field({ label, value }) {
  return (
    <div>
      <dt className="text-xs font-medium text-slate-500 uppercase tracking-wide">{label}</dt>
      <dd className="mt-1 text-sm text-slate-900">{value}</dd>
    </div>
  )
}

export default function Customer() {
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

  const fullName = customer ? `${customer.firstName} ${customer.lastName}` : `Customer #${id}`

  return (
    <div>
      {/* Breadcrumb */}
      <nav className="flex items-center gap-1.5 text-sm text-slate-500 mb-6">
        <Link to="/customers" className="hover:text-indigo-600 transition-colors">Customers</Link>
        <svg className="w-4 h-4 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
        </svg>
        <span className="text-slate-800 font-medium">{fullName}</span>
      </nav>

      {custLoading && <Spinner message="Loading customer…" />}
      {custError   && <Alert error={custError} />}

      {customer && (
        <div className="bg-white rounded-xl border border-slate-200 shadow-sm p-6 mb-6">
          <div className="flex items-start gap-4">
            {/* Avatar */}
            <div className="w-14 h-14 rounded-full bg-indigo-100 flex items-center justify-center
                            text-indigo-700 font-bold text-lg flex-shrink-0">
              {customer.firstName?.[0]}{customer.lastName?.[0]}
            </div>
            {/* Info */}
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-3 mb-1">
                <h1 className="text-xl font-bold text-slate-900">{customer.firstName} {customer.lastName}</h1>
                <Badge value={customer.status} />
              </div>
              <p className="text-sm text-slate-500">Customer #{id}</p>
            </div>
          </div>

          <div className="mt-5 pt-5 border-t border-slate-100 grid grid-cols-2 md:grid-cols-4 gap-5">
            <Field label="Email" value={customer.email} />
            <Field label="Phone" value={customer.phone || '—'} />
            <Field label="Date of birth" value={fmt(customer.dateOfBirth)} />
            <Field label="Member since" value={fmt(customer.createdAt)} />
          </div>
        </div>
      )}

      {/* Accounts */}
      <div className="flex items-baseline justify-between mb-4">
        <h2 className="text-lg font-semibold text-slate-800">Accounts</h2>
        {accounts && (
          <span className="text-sm text-slate-500">
            {accounts.page.totalElements} account{accounts.page.totalElements !== 1 ? 's' : ''}
          </span>
        )}
      </div>

      {accLoading && <Spinner message="Loading accounts…" />}
      {accError   && <Alert error={accError} onRetry={loadAccounts} />}

      {!accLoading && !accError && accounts && (
        <>
          <div className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden">
            <table className="min-w-full">
              <thead>
                <tr className="border-b border-slate-200 bg-slate-50">
                  {['Account Number', 'Type', 'Balance', 'Status', 'Opened', ''].map(h => (
                    <th key={h}
                        className="px-5 py-3 text-left text-xs font-semibold text-slate-500 uppercase tracking-wider">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {accounts.content.length === 0 ? (
                  <tr>
                    <td colSpan={6} className="text-center py-16 text-slate-400 text-sm">
                      No accounts found.
                    </td>
                  </tr>
                ) : accounts.content.map(a => (
                  <tr key={a.id} className="hover:bg-slate-50 transition-colors">
                    <td className="px-5 py-3.5 font-mono text-sm font-medium text-slate-900">
                      {a.accountNumber}
                    </td>
                    <td className="px-5 py-3.5 text-sm text-slate-600">{a.accountType}</td>
                    <td className="px-5 py-3.5 text-sm font-semibold text-slate-900">
                      {currency(a.balance, a.currency)}
                    </td>
                    <td className="px-5 py-3.5"><Badge value={a.status} /></td>
                    <td className="px-5 py-3.5 text-sm text-slate-500">{fmt(a.createdAt)}</td>
                    <td className="px-5 py-3.5 text-right">
                      <Link
                        to={`/accounts/${a.id}`}
                        className="text-sm font-medium text-indigo-600 hover:text-indigo-700"
                      >
                        View
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pager
            page={accounts.page.number}
            totalPages={accounts.page.totalPages}
            onPageChange={setAccPage}
          />
        </>
      )}
    </div>
  )
}
