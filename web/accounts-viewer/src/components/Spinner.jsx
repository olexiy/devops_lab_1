export default function Spinner({ message = 'Loading…' }) {
  return (
    <div className="flex flex-col items-center justify-center py-20 gap-3 text-slate-400">
      <div className="w-8 h-8 border-[3px] border-slate-200 border-t-indigo-500 rounded-full animate-spin" />
      <span className="text-sm">{message}</span>
    </div>
  )
}
