import { Link, useLocation } from 'react-router-dom'

export default function Navbar() {
  const { pathname } = useLocation()
  const linkClass = pathname.startsWith('/customers')
    ? 'text-white font-semibold'
    : 'text-blue-100 hover:text-white'

  return (
    <nav className="bg-blue-700 shadow">
      <div className="max-w-7xl mx-auto px-4 h-14 flex items-center gap-8">
        <span className="text-white text-lg font-bold tracking-tight">
          Rating Dashboard
        </span>
        <Link to="/customers" className={`text-sm ${linkClass}`}>
          Customers
        </Link>
      </div>
    </nav>
  )
}
