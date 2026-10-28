import { useState, type ReactNode } from 'react'
import { Icon, type IconName } from './icons'

// ─── Componentes compartidos ─────────────────────────────────────────────────
// Solo presentación: los datos y cálculos llegan ya hechos desde stats.ts / nutrition.ts.

export interface TableSpec {
  columns: string[]
  rows: (string | number)[][]
  numeric?: boolean[] // columnas alineadas a la derecha
}

/** Tarjeta de gráfico con vista de tabla alternativa (los datos nunca dependen solo del hover). */
export function ChartCard({ title, subtitle, table, children, wide = false, icon }: {
  title: string
  subtitle?: string
  table?: TableSpec
  children: ReactNode
  wide?: boolean
  icon?: IconName
}) {
  const [asTable, setAsTable] = useState(false)
  return (
    <section className={`card${wide ? ' wide' : ''}`}>
      <header className="card-head">
        <div className="card-title">
          {icon && <span className="card-icon"><Icon name={icon} size={18} /></span>}
          <div>
            <h2>{title}</h2>
            {subtitle && <p className="subtitle">{subtitle}</p>}
          </div>
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

export type Tone = 'blue' | 'teal' | 'amber' | 'violet' | 'coral'

export function StatTile({ label, value, hint, icon, tone = 'blue' }: { label: string; value: string; hint?: string; icon?: IconName; tone?: Tone }) {
  return (
    <div className="stat">
      {icon && <span className={`stat-icon tone-${tone}`}><Icon name={icon} size={18} /></span>}
      <div className="stat-text">
        <div className="stat-label">{label}</div>
        <div className="stat-value">{value}</div>
        {hint && <div className="stat-hint">{hint}</div>}
      </div>
    </div>
  )
}

export function EmptyState({ icon, title, children }: { icon: IconName; title: string; children?: ReactNode }) {
  return (
    <div className="empty-state">
      <span className="empty-icon"><Icon name={icon} size={26} /></span>
      <strong>{title}</strong>
      {children && <p>{children}</p>}
    </div>
  )
}

/** Bloques grises animados mientras llegan los datos (la animación se apaga con "reducir movimiento"). */
export function Skeleton({ rows = 3, label = 'Cargando…' }: { rows?: number; label?: string }) {
  return (
    <div className="skeleton-wrap" aria-busy="true" aria-label={label}>
      <div className="skeleton skeleton-hero" />
      <div className="skeleton-row">
        {Array.from({ length: rows }, (_, i) => <div key={i} className="skeleton skeleton-tile" />)}
      </div>
      <div className="skeleton skeleton-block" />
    </div>
  )
}

export function Badge({ tone = 'neutral', icon, children, title }: { tone?: 'neutral' | 'blue' | 'teal' | 'amber' | 'coral'; icon?: IconName; children: ReactNode; title?: string }) {
  return (
    <span className={`badge badge-${tone}`} title={title}>
      {icon && <Icon name={icon} size={13} />}
      {children}
    </span>
  )
}

/** Anillo de progreso (SVG). pct puede pasar de 100: el anillo se llena y cambia a "excedido". */
export function ProgressRing({ pct, size = 168, stroke = 14, over = false, children, label }: {
  pct: number; size?: number; stroke?: number; over?: boolean; children: ReactNode; label: string
}) {
  const r = (size - stroke) / 2
  const c = 2 * Math.PI * r
  const shown = Math.max(0, Math.min(pct, 100))
  return (
    <div className={`pring${over ? ' over' : ''}`} role="img" aria-label={label}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden="true">
        <defs>
          <linearGradient id="pring-grad" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" className="pring-stop-a" />
            <stop offset="100%" className="pring-stop-b" />
          </linearGradient>
        </defs>
        <circle cx={size / 2} cy={size / 2} r={r} className="pring-track" strokeWidth={stroke} fill="none" />
        <circle
          cx={size / 2} cy={size / 2} r={r} className="pring-value" strokeWidth={stroke} fill="none"
          strokeDasharray={`${(shown / 100) * c} ${c}`} strokeLinecap="round" transform={`rotate(-90 ${size / 2} ${size / 2})`}
        />
      </svg>
      <div className="pring-center">{children}</div>
    </div>
  )
}
