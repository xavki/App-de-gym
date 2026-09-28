import { useState, type ReactNode } from 'react'

export interface TableSpec {
  columns: string[]
  rows: (string | number)[][]
  numeric?: boolean[] // columnas alineadas a la derecha
}

/** Tarjeta de gráfico con vista de tabla alternativa (los datos nunca dependen solo del hover). */
export function ChartCard({ title, subtitle, table, children, wide = false }: {
  title: string
  subtitle?: string
  table?: TableSpec
  children: ReactNode
  wide?: boolean
}) {
  const [asTable, setAsTable] = useState(false)
  return (
    <section className={`card${wide ? ' wide' : ''}`}>
      <header className="card-head">
        <div>
          <h2>{title}</h2>
          {subtitle && <p className="subtitle">{subtitle}</p>}
        </div>
        {table && (
          <button className="link-btn" onClick={() => setAsTable((v) => !v)} aria-pressed={asTable}>
            {asTable ? 'Ver gráfico' : 'Ver tabla'}
          </button>
        )}
      </header>
      {asTable && table ? <DataTable {...table} /> : children}
    </section>
  )
}

export function DataTable({ columns, rows, numeric = [] }: TableSpec) {
  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>{columns.map((c, i) => <th key={c} className={numeric[i] ? 'num' : undefined}>{c}</th>)}</tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i}>{r.map((v, j) => <td key={j} className={numeric[j] ? 'num' : undefined}>{v}</td>)}</tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export function StatTile({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="stat">
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {hint && <div className="stat-hint">{hint}</div>}
    </div>
  )
}
