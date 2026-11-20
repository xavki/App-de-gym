import type { ReactNode } from 'react'

/** Tooltip flotante: el valor manda, la etiqueta acompaña. React escapa el texto. */
export function Tooltip({ x, y, width, children }: { x: number; y: number; width: number; children: ReactNode }) {
  const flip = x > width - 180
  return (
    <div className="tooltip" style={{ left: flip ? undefined : x + 12, right: flip ? width - x + 12 : undefined, top: y }}>
      {children}
    </div>
  )
}

export function TooltipRow({ color, value, label, kind = 'line' }: { color?: string; value: string; label: string; kind?: 'line' | 'none' }) {
  return (
    <div className="tt-row">
      {kind === 'line' && color && <span className="tt-key" style={{ background: color }} />}
      <strong>{value}</strong>
      <span className="tt-label">{label}</span>
    </div>
  )
}
