export default function ErrorMessage({ error, onRetry }) {
  const message = error?.message ?? 'An unexpected error occurred.'
  return (
    <div className="rounded-lg bg-red-50 border border-red-200 p-4 flex flex-col gap-2">
      <p className="text-sm font-medium text-red-800">{message}</p>
      {onRetry && (
        <button
          onClick={onRetry}
          className="self-start text-xs text-red-700 underline hover:no-underline"
        >
          Try again
        </button>
      )}
    </div>
  )
}
