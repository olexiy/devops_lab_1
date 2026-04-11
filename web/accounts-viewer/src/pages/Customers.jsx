import { useState, useEffect, useCallback } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { fetchCustomers } from '../api.js'
import Badge from '../components/Badge.jsx'
import Spinner from '../components/Spinner.jsx'
import Alert from '../components/Alert.jsx'
import Pager from '../components/Pager.jsx'

const STATUS_OPTIONS = ['', 'ACTIVE', 'INACTIVE', 'BLOCKED', 'FROZEN', 'CLOSED']
const PAGE_SIZE = 20

function Initials({ firstName, lastName }) {
  const letters = `${firstName?.[0] ?? ''}${lastName?.[0] ?? ''}`.toUpperCase()
  const palette = [
    'bg-indigo-100 text-indigo-700',
    'bg-emerald-100 text-emerald-700',
    'bg-amber-100 text-amber-700',
    'bg-sky-100 text-sky-700',
    'bg-pink-100 text-pink-700',
    'bg-violet-100 text-violet-700',
  ]
  const idx = (firstName?.charCodeAt(0) ?? 0) % palette.length
  return (
    <span className={`inline-flex w-8 h-8 rounded-full items-center justify-center text-xs font-semibold flex-shrink-0 ${palette[idx]}`}>
      {letters || '?'}
    </span>
  )
}

export default function Customers() {
  const [searchParams, setSearchParams] = useSearchParams()
  const page     = parseInt(searchParams.get('page') ?? '0', 10)
  const lastName = searchParams.get('lastName') ?? ''
  const status   = searchParams.get('status') ?? ''

  const [input,   setInput]   = useState(lastName)
  const [data,    setData]    = useState(null)
  const [loading, setLoading] = useState(true)
  const [error,   setError]   = useState(null)

  const load = useCallback(() => {
    setLoading(true)
    setError(null)
    fetchCustomers({ page, size: PAGE_SIZE, lastName, status })
      .then(setData)
      .catch(setError)
      .finally(() => setLoading(false))
  }, [page, lastName, status])

  useEffect(() => { load() }, [load])

  function applySearch(e) {
    e.preventDefault()
    setSearchParams({ page: 0, lastName: input, status })
  }

  return (
    <div>
      {/* Header */}
      <div className="flex items-baseline justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">Customers</h1>
          {data && (
            <p className="text-sm text-slate-500 mt-0.5">
              {data.page.totalElements.toLocaleString()} total records
            </p>
          )}
        </div>
      </div>

      {/* Filters */}
      <div className="bg-white rounded-xl border border-slate-200 shadow-sm p-4 mb-5">
        <form onSubmit={applySearch} className="flex flex-wrap gap-3">
          <div className="relative">
            <svg className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400"
              fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                d="M21 21l-4.35-4.35M17 11A6 6 0 111 11a6 6 0 0116 0z" />
            </svg>
            <input
              type="text"
              placeholder="Search by last name…"
              value={input}
              onChange={e => setInput(e.target.value)}
              className="pl-9 pr-4 py-2 text-sm rounded-lg border border-slate-200 w-56
                         focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent"
            />
          </div>
          <select
            value={status}
            onChange={e => setSearchParams({ page: 0, lastName, status: e.target.value })}
            className="px-3 py-2 text-sm rounded-lg border border-slate-200
                       focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent"
          >
            {STATUS_OPTIONS.map(s => (
              <option key={s} value={s}>{s || 'All statuses'}</option>
            ))}
          </select>
          <button
            type="submit"
            className="px-4 py-2 text-sm font-medium bg-indigo-600 text-white rounded-lg
                       hover:bg-indigo-700 transition-colors"
          >
            Search
          </button>
        </form>
      </div>

      {loading && <Spinner />}
      {error   && <Alert error={error} onRetry={load} />}

      {!loading && !error && data && (
        <>
          <div className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden">
            <table className="min-w-full">
              <thead>
                <tr className="border-b border-slate-200 bg-slate-50">
                  {['Customer', 'Email', 'Phone', 'Status', 'Member since', ''].map(h => (
                    <th key={h}
                        className="px-5 py-3 text-left text-xs font-semibold text-slate-500 uppercase tracking-wider">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {data.content.length === 0 ? (
                  <tr>
                    <td colSpan={6} className="text-center py-16 text-slate-400 text-sm">
                      No customers found.
                    </td>
                  </tr>
                ) : data.content.map(c => (
                  <tr key={c.id} className="hover:bg-slate-50 transition-colors">
                    <td className="px-5 py-3.5">
                      <div className="flex items-center gap-3">
                        <Initials firstName={c.firstName} lastName={c.lastName} />
                        <div>
                          <p className="text-sm font-medium text-slate-900">
                            {c.firstName} {c.lastName}
                          </p>
                          <p className="text-xs text-slate-400">#{c.id}</p>
                        </div>
                      </div>
                    </td>
                    <td className="px-5 py-3.5 text-sm text-slate-600">{c.email}</td>
                    <td className="px-5 py-3.5 text-sm text-slate-500">{c.phone || '—'}</td>
                    <td className="px-5 py-3.5"><Badge value={c.status} /></td>
                    <td className="px-5 py-3.5 text-sm text-slate-500">
                      {c.createdAt ? new Date(c.createdAt).toLocaleDateString() : '—'}
                    </td>
                    <td className="px-5 py-3.5 text-right">
                      <Link
                        to={`/customers/${c.id}`}
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
            page={data.page.number}
            totalPages={data.page.totalPages}
            onPageChange={p => setSearchParams({ page: p, lastName, status })}
          />
        </>
      )}
    </div>
  )
}
