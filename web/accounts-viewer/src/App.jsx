import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import Layout from './components/Layout.jsx'
import Customers from './pages/Customers.jsx'
import Customer from './pages/Customer.jsx'
import Account from './pages/Account.jsx'

export default function App() {
  return (
    <BrowserRouter>
      <Layout>
        <Routes>
          <Route path="/" element={<Navigate to="/customers" replace />} />
          <Route path="/customers" element={<Customers />} />
          <Route path="/customers/:id" element={<Customer />} />
          <Route path="/accounts/:id" element={<Account />} />
        </Routes>
      </Layout>
    </BrowserRouter>
  )
}
