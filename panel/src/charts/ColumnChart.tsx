import { useState } from 'react'
import { Tooltip, TooltipRow } from './common'
import { linear, niceTicks, roundedTopRect, useWidth } from './geometry'

export interface Column {
  key: string
  label: string        // etiqueta del eje X
  title: string        // encabezado del tooltip
  value: number
  extra?: string[]     // líneas secundarias del tooltip
}

interface Props {
  data: Column[]
  format: (v: number) => string
  axisFormat?: (v: number) => string
  height?: number
  color?: string
}

const M = { top: 20, right: 8, bottom: 28, left: 48 }

export function ColumnChart({ data, format, axisFormat = format, height = 220, color = 'var(--series-1)' }: Props) {
  const [ref, width] = useWidth<HTMLDivElement>()
  const [hover, setHover] = useState<number | null>(null)

  if (!data.length) return <div ref={ref} className="empty">Sin datos en este rango</div>

  const max = Math.max(...data.map((d) => d.value))
  const ticks = niceTicks(0, max || 1)
  const innerW = Math.max(0, width - M.left - M.right)
  const innerH = height - M.top - M.bottom
  const band = innerW / data.length
  const barW = Math.max(2, Math.min(24, band - 2)) // máx. 24px; el resto del hueco es aire
  const y = linear(0, ticks.at(-1)!, M.top + innerH, M.top)
  const every = Math.ceil(data.length / Math.max(1, Math.floor(innerW / 56)))
  const maxIdx = data.findIndex((d) => d.value === max)

  return (
    <div ref={ref} className="chart">
      {width > 0 && (
        <svg width={width} height={height} role="img">
          {ticks.map((t) => (
            <g key={t}>
              <line x1={M.left} x2={M.left + innerW} y1={y(t)} y2={y(t)} className={t === 0 ? 'baseline' : 'gridline'} />
              <text x={M.left - 8} y={y(t)} className="axis-label" textAnchor="end" dominantBaseline="middle">{axisFormat(t)}</text>
            </g>
          ))}
          {data.map((d, i) => {
            const cx = M.left + band * i + band / 2
            const h = y(0) - y(d.value)
            return (
              <g key={d.key}>
                <path d={roundedTopRect(cx - barW / 2, y(d.value), barW, h)} fill={color} className={hover === i ? 'mark hovered' : 'mark'} />
                {i % every === 0 && (
                  <text x={cx} y={height - 8} className="axis-label" textAnchor="middle">{d.label}</text>
                )}
                {i === maxIdx && max > 0 && (
                  <text x={cx} y={y(d.value) - 6} className="value-label" textAnchor="middle">{format(d.value)}</text>
                )}
                {/* El hueco completo es la zona de hover, no solo la barra pintada */}
                <rect
                  x={M.left + band * i} y={M.top} width={band} height={innerH} fill="transparent" className="hit"
                  tabIndex={0} aria-label={`${d.title}: ${format(d.value)}`}
                  onPointerEnter={() => setHover(i)} onPointerLeave={() => setHover(null)}
                  onFocus={() => setHover(i)} onBlur={() => setHover(null)}
                />
              </g>
            )
          })}
        </svg>
      )}
      {hover != null && (
        <Tooltip x={M.left + band * hover + band / 2} y={Math.max(0, y(data[hover].value) - 40)} width={width}>
          <div className="tt-title">{data[hover].title}</div>
          <TooltipRow value={format(data[hover].value)} label="" kind="none" />
          {data[hover].extra?.map((e) => <div key={e} className="tt-extra">{e}</div>)}
        </Tooltip>
      )}
    </div>
  )
}
