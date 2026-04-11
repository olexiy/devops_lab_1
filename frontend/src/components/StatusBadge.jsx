const COLORS = {
  // Customer / Account status
  ACTIVE:       'bg-green-100 text-green-800',
  INACTIVE:     'bg-gray-100 text-gray-600',
  BLOCKED:      'bg-red-100 text-red-800',
  CLOSED:       'bg-slate-100 text-slate-600',
  FROZEN:       'bg-yellow-100 text-yellow-800',
  // Transaction types
  CREDIT:       'bg-green-100 text-green-800',
  DEBIT:        'bg-red-100 text-red-800',
  TRANSFER_IN:  'bg-blue-100 text-blue-800',
  TRANSFER_OUT: 'bg-orange-100 text-orange-800',
  FEE:          'bg-purple-100 text-purple-800',
  // Risk levels
  LOW:          'bg-green-100 text-green-800',
  MEDIUM:       'bg-yellow-100 text-yellow-800',
  HIGH:         'bg-orange-100 text-orange-800',
  CRITICAL:     'bg-red-100 text-red-800',
}

export default function StatusBadge({ value }) {
  if (!value) return null
  const color = COLORS[value] ?? 'bg-gray-100 text-gray-600'
  return (
    <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${color}`}>
      {value}
    </span>
  )
}
