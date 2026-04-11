export default function Pager({ page, totalPages, onPageChange }) {
  if (totalPages <= 1) return null

  return (
    <div className="flex items-center justify-between mt-4 px-1">
      <span className="text-sm text-slate-500">
        Page {page + 1} of {totalPages}
      </span>
      <div className="flex gap-1.5">
        <button
          onClick={() => onPageChange(page - 1)}
          disabled={page === 0}
          className="px-3 py-1.5 text-sm rounded-lg border border-slate-200 bg-white text-slate-700
                     hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
        >
          Previous
        </button>
        <button
          onClick={() => onPageChange(page + 1)}
          disabled={page >= totalPages - 1}
          className="px-3 py-1.5 text-sm rounded-lg border border-slate-200 bg-white text-slate-700
                     hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
        >
          Next
        </button>
      </div>
    </div>
  )
}
