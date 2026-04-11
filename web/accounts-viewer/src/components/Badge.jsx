const VARIANTS = {
  // Customer / Account status
  ACTIVE:       'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  INACTIVE:     'bg-slate-100 text-slate-600 ring-slate-500/20',
  BLOCKED:      'bg-red-50 text-red-700 ring-red-600/20',
  CLOSED:       'bg-slate-100 text-slate-500 ring-slate-400/20',
  FROZEN:       'bg-amber-50 text-amber-700 ring-amber-600/20',
  // Transaction types
  CREDIT:       'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  DEBIT:        'bg-red-50 text-red-700 ring-red-600/20',
  TRANSFER_IN:  'bg-blue-50 text-blue-700 ring-blue-600/20',
  TRANSFER_OUT: 'bg-orange-50 text-orange-700 ring-orange-600/20',
  FEE:          'bg-purple-50 text-purple-700 ring-purple-600/20',
  // Risk levels
  LOW:          'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  MEDIUM:       'bg-amber-50 text-amber-700 ring-amber-600/20',
  HIGH:         'bg-orange-50 text-orange-700 ring-orange-600/20',
  CRITICAL:     'bg-red-50 text-red-700 ring-red-600/20',
}

export default function Badge({ value }) {
  if (!value) return null
  const cls = VARIANTS[value] ?? 'bg-slate-100 text-slate-600 ring-slate-500/20'
  return (
    <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${cls}`}>
      {value.replace(/_/g, '\u202f')}
    </span>
  )
}
