import { useState, useEffect, useCallback } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { fetchCustomers } from '../api.js'
import Pagination from '../components/Pagination.jsx'
import StatusBadge from '../components/StatusBadge.jsx'
import LoadingSpinner from '../components/LoadingSpinner.jsx'
import ErrorMessage from '../components/ErrorMessage.jsx'

const STATUS_OPTIONS = ['', 'ACTIVE', 'INACTIVE', 'BLOCKED', 'CLOSED']
const PAGE_SIZE = 20

export default function CustomerList() {
  const [searchParams, setSearchParams] = useSearchParams()

  const page     = parseInt(searchParams.get('page')     ?? '0', 10)
  const lastName = searchParams.get('lastName') ?? ''
  const status   = searchParams.get('status')   ?? ''

  const [lastNameInput, setLastNameInput] = useState(lastName)
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

  function applyFilter(e) {
    e.preventDefault()
    setSearchParams({ page: 0, lastName: lastNameInput, status })
  }

  function handleStatusChange(e) {
    setSearchParams({ page: 0, lastName, status: e.target.value })
  }

  function handlePageChange(newPage) {
    setSearchParams({ page: newPage, lastName, status })
  }

  return (
    <div>
      <h1 className="text-2xl font-semibold text-gray-900 mb-6">Customers</h1>

      <form onSubmit={applyFilter} className="flex flex-wrap gap-3 mb-6">
        <input
          type="text"
          placeholder="Search by last name…"
          value={lastNameInput}
          onChange={e => setLastNameInput(e.target.value)}
          className="border border-gray-300 rounded-md px-3 py-2 text-sm w-56
                     focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <select
          value={status}
          onChange={handleStatusChange}
          className="border border-gray-300 rounded-md px-3 py-2 text-sm
                     focus:outline-none focus:ring-2 focus:ring-blue-500"
        >
          {STATUS_OPTIONS.map(s => (
            <option key={s} value={s}>{s || 'All statuses'}</option>
          ))}
        </select>
        <button
          type="submit"
          className="px-4 py-2 text-sm bg-blue-600 text-white rounded-md
                     hover:bg-blue-700 transition-colors"
        >
          Search
        </button>
      </form>

      {loading && <LoadingSpinner />}
      {error   && <ErrorMessage error={error} onRetry={load} />}

      {!loading && !error && data && (
        <>
          <div className="bg-white rounded-lg shadow-sm border border-gray-200 overflow-hidden">
            <table className="min-w-full divide-y divide-gray-200">
              <thead className="bg-gray-50">
                <tr>
                  {['Name', 'Email', 'Status', 'Member since', ''].map(h => (
                    <th key={h}
                        className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {data.content.length === 0 ? (
                  <tr>
                    <td colSpan={5} className="text-center py-10 text-gray-500 text-sm">
                      No customers found.
                    </td>
                  </tr>
                ) : data.content.map(c => (
                  <tr key={c.id} className="hover:bg-gray-50">
                    <td className="px-4 py-3 text-sm font-medium text-gray-900">
                      {c.firstName} {c.lastName}
                    </td>
                    <td className="px-4 py-3 text-sm text-gray-600">{c.email}</td>
                    <td className="px-4 py-3"><StatusBadge value={c.status} /></td>
                    <td className="px-4 py-3 text-sm text-gray-500">
                      {c.createdAt ? new Date(c.createdAt).toLocaleDateString() : '—'}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <Link to={`/customers/${c.id}`} className="text-sm text-blue-600 hover:underline">
                        View
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="mt-2 text-xs text-gray-500">
            {data.page.totalElements.toLocaleString()} total customers
          </div>

          <Pagination
            page={data.page.number}
            totalPages={data.page.totalPages}
            onPageChange={handlePageChange}
          />
        </>
      )}
    </div>
  )
}
