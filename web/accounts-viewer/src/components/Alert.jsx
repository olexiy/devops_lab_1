export default function Alert({ error, onRetry }) {
  return (
    <div className="rounded-xl bg-red-50 border border-red-200 p-4 flex items-start gap-3">
      <svg className="w-5 h-5 text-red-500 flex-shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
          d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126zM12 15.75h.007v.008H12v-.008z" />
      </svg>
      <div className="flex-1 min-w-0">
        <p className="text-sm font-medium text-red-800">{error?.message ?? 'An unexpected error occurred.'}</p>
        {onRetry && (
          <button onClick={onRetry} className="mt-1 text-xs text-red-700 underline underline-offset-2 hover:no-underline">
            Try again
          </button>
        )}
      </div>
    </div>
  )
}
