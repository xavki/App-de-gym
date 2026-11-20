import { useState } from 'react'
import { Tooltip, TooltipRow } from './common'
import { linear, niceTicks, useWidth } from './geometry'

export interface LineSeries {
  id: string
  label: string
  color: string // variable CSS, p. ej. "var(--series-1)"
  points: { x: Date; y: number }[]
}

interface Props {
  series: LineSeries[]
  format: (v: number) => string
  height?: number
  /** Eje Y desde cero (volúmenes) o ajustado a los datos (pesos, 1RM). */
  zeroBased?: boolean
  area?: boolean
}

const M = { top: 12, right: 64, bottom: 28, left: 48 }
const fmtX = new Intl.DateTimeFormat('es', { day: 'numeric', month: 'short' })

export function LineChart({ series, format, height = 240, zeroBased = false, area = false }: Props) {
  const [ref, width] = useWidth<HTMLDivElement>()
  const [hover, setHover] = useState<number | null>(null)

  const all = series.flatMap((s) => s.points)
  if (!all.length) return <div ref={ref} className="empty">Sin datos en este rango</div>

  const xs = [...new Set(all.map((p) => p.x.getTime()))].sort((a, b) => a - b)
  const ysAll = all.map((p) => p.y)
  const pad = (Math.max(...ysAll) - Math.min(...ysAll)) * 0.1 || 1
  const ticks = niceTicks(zeroBased ? 0 : Math.min(...ysAll) - pad, Math.max(...ysAll) + pad)
  const innerW = Math.max(0, width - M.left - M.right)
  const innerH = height - M.top - M.bottom
  const x = linear(xs[0], xs.at(-1)!, M.left, M.left + innerW)
  const y = linear(ticks[0], ticks.at(-1)!, M.top + innerH, M.top)

  // Etiquetas del eje X: 2-5 según ancho
  const nX = Math.max(2, Math.min(5, Math.floor(innerW / 90)))
  const xTicks = xs.length <= nX ? xs : Array.from({ length: nX }, (_, i) => xs[Math.round((i * (xs.length - 1)) / (nX - 1))])

  const onMove = (e: React.PointerEvent<SVGRectElement>) => {
    const px = e.clientX - e.currentTarget.getBoundingClientRect().left + M.left
    let best = 0
    xs.forEach((t, i) => { if (Math.abs(x(t) - px) < Math.abs(x(xs[best]) - px)) best = i })
    setHover(best)
  }

  // Etiquetas finales directas solo si no se pisan (si se pisan, manda la leyenda)
  const ends = series.map((s) => s.points.at(-1)).filter(Boolean).map((p) => y(p!.y))
  const endLabels = series.length <= 4 && ends.every((a, i) => ends.every((b, j) => i === j || Math.abs(a - b) > 14))

  const hx = hover != null ? xs[hover] : null

  return (
    <div ref={ref} className="chart">
      {series.length > 1 && (
        <div className="legend">
          {series.map((s) => (
            <span key={s.id} className="legend-item"><span className="legend-line" style={{ background: s.color }} />{s.label}</span>
          ))}
        </div>
      )}
      {width > 0 && (
        <svg width={width} height={height} role="img" aria-label={series.map((s) => s.label).join(', ')}>
          {ticks.map((t) => (
            <g key={t}>
              <line x1={M.left} x2={M.left + innerW} y1={y(t)} y2={y(t)} className="gridline" />
              <text x={M.left - 8} y={y(t)} className="axis-label" textAnchor="end" dominantBaseline="middle">{format(t)}</text>
            </g>
          ))}
          {xTicks.map((t) => (
            <text key={t} x={x(t)} y={height - 8} className="axis-label" textAnchor="middle">{fmtX.format(new Date(t))}</text>
          ))}

          {series.map((s) => {
            const d = s.points.map((p, i) => `${i ? 'L' : 'M'}${x(p.x.getTime())},${y(p.y)}`).join('')
            const last = s.points.at(-1)
            return (
              <g key={s.id}>
                {area && s.points.length > 1 && (
                  <path d={`${d}L${x(s.points.at(-1)!.x.getTime())},${y(ticks[0])}L${x(s.points[0].x.getTime())},${y(ticks[0])}Z`} fill={s.color} opacity={0.1} />
                )}
                <path d={d} fill="none" stroke={s.color} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
                {s.points.length === 1 && <circle cx={x(s.points[0].x.getTime())} cy={y(s.points[0].y)} r={4} fill={s.color} className="ring" />}
                {last && (
                  <>
                    <circle cx={x(last.x.getTime())} cy={y(last.y)} r={4} fill={s.color} className="ring" />
                    {endLabels && (
                      <text x={x(last.x.getTime()) + 8} y={y(last.y)} className="end-label" dominantBaseline="middle">{format(last.y)}</text>
                    )}
                  </>
                )}
              </g>
            )
          })}

          {hx != null && (
            <g pointerEvents="none">
              <line x1={x(hx)} x2={x(hx)} y1={M.top} y2={M.top + innerH} className="crosshair" />
              {series.map((s) => {
                const p = s.points.find((p) => p.x.getTime() === hx)
                return p && <circle key={s.id} cx={x(hx)} cy={y(p.y)} r={5} fill={s.color} className="ring" />
              })}
            </g>
          )}

          <rect
            x={M.left} y={M.top} width={innerW} height={innerH} fill="transparent"
            onPointerMove={onMove} onPointerLeave={() => setHover(null)}
            tabIndex={0} className="hit" aria-label="Recorrer valores con las flechas"
            onFocus={() => setHover(xs.length - 1)} onBlur={() => setHover(null)}
            onKeyDown={(e) => {
              if (e.key === 'ArrowLeft') setHover((h) => Math.max(0, (h ?? 0) - 1))
              if (e.key === 'ArrowRight') setHover((h) => Math.min(xs.length - 1, (h ?? 0) + 1))
            }}
          />
        </svg>
      )}
      {hx != null && (
        <Tooltip x={x(hx)} y={M.top + (series.length > 1 ? 28 : 0)} width={width}>
          <div className="tt-title">{new Intl.DateTimeFormat('es', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' }).format(new Date(hx))}</div>
          {series.map((s) => {
            const p = s.points.find((p) => p.x.getTime() === hx)
            return <TooltipRow key={s.id} color={s.color} value={p ? format(p.y) : '—'} label={s.label} />
          })}
        </Tooltip>
      )}
    </div>
  )
}
