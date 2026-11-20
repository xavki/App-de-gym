import { useState } from 'react'
import { Tooltip } from './common'
import { roundedRightRect, useWidth } from './geometry'

interface Props {
  data: { label: string; value: number }[]
  format: (v: number) => string
  color?: string
}

const ROW = 28
const BAR = 16
const LABEL_W = 110
const VALUE_W = 56

/** Barras horizontales ordenadas: cada valor va rotulado en la punta. */
export function BarList({ data, format, color = 'var(--series-1)' }: Props) {
  const [ref, width] = useWidth<HTMLDivElement>()
  const [hover, setHover] = useState<number | null>(null)
  if (!data.length) return <div ref={ref} className="empty">Sin datos en este rango</div>

  const max = Math.max(...data.map((d) => d.value))
  const total = data.reduce((a, d) => a + d.value, 0)
  const barMax = Math.max(0, width - LABEL_W - VALUE_W)
  const height = data.length * ROW

  return (
    <div ref={ref} className="chart">
      {width > 0 && (
        <svg width={width} height={height} role="img">
          <line x1={LABEL_W} x2={LABEL_W} y1={0} y2={height} className="baseline" />
          {data.map((d, i) => {
            const w = (d.value / max) * barMax
            const cy = i * ROW + ROW / 2
            return (
              <g key={d.label}>
                <text x={LABEL_W - 8} y={cy} className="axis-label strong" textAnchor="end" dominantBaseline="middle">{d.label}</text>
                <path d={roundedRightRect(LABEL_W, cy - BAR / 2, w, BAR)} fill={color} className={hover === i ? 'mark hovered' : 'mark'} />
                <text x={LABEL_W + w + 6} y={cy} className="value-label" dominantBaseline="middle">{format(d.value)}</text>
                <rect
                  x={0} y={i * ROW} width={width} height={ROW} fill="transparent" className="hit"
                  tabIndex={0} aria-label={`${d.label}: ${format(d.value)}`}
                  onPointerEnter={() => setHover(i)} onPointerLeave={() => setHover(null)}
                  onFocus={() => setHover(i)} onBlur={() => setHover(null)}
                />
              </g>
            )
          })}
        </svg>
      )}
      {hover != null && (
        <Tooltip x={LABEL_W + (data[hover].value / max) * barMax} y={hover * ROW} width={width}>
          <div className="tt-title">{data[hover].label}</div>
          <div className="tt-row"><strong>{format(data[hover].value)}</strong><span className="tt-label">{Math.round((data[hover].value / total) * 100)} % del total</span></div>
        </Tooltip>
      )}
    </div>
  )
}
