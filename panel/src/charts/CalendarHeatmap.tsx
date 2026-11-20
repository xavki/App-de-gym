import { useState } from 'react'
import { DAY, dayKey, fmtInt, fmtVolume, startOfWeek } from '../stats'
import { Tooltip } from './common'
import { useWidth } from './geometry'

interface Props {
  range: [Date, Date]
  days: Map<string, { volume: number; sets: number; names: string[] }>
}

const GAP = 2
const LEFT = 28
const TOP = 16
const DOW = ['L', '', 'X', '', 'V', '', 'D']
const LEVELS = 4

/** Calendario tipo Garmin/GitHub: una columna por semana, intensidad = volumen del día. */
export function CalendarHeatmap({ range, days }: Props) {
  const [ref, width] = useWidth<HTMLDivElement>()
  const [hover, setHover] = useState<{ x: number; y: number; key: string; date: Date } | null>(null)

  const first = startOfWeek(range[0])
  const weeks = Math.ceil((range[1].getTime() - first.getTime() + DAY) / (7 * DAY))
  // Casillas cuadradas que llenan el ancho (entre 10 y 28 px; si no caben, scroll horizontal)
  const cell = Math.max(10, Math.min(28, Math.floor((width - LEFT) / weeks) - GAP))
  const svgW = LEFT + weeks * (cell + GAP)
  const svgH = TOP + 7 * (cell + GAP)

  // Umbrales por cuartiles del volumen de los días entrenados
  const vols = [...days.values()].map((d) => d.volume).sort((a, b) => a - b)
  const q = (p: number) => vols[Math.min(vols.length - 1, Math.floor(p * vols.length))] ?? 0
  const cuts = [q(0.25), q(0.5), q(0.75)]
  const level = (v: number) => 1 + cuts.filter((c) => v > c).length

  const fmtMonth = new Intl.DateTimeFormat('es', { month: 'short' })
  const cells: React.ReactNode[] = []
  const months: React.ReactNode[] = []
  let lastMonth = -1

  const scrollX = () => ref.current?.scrollLeft ?? 0

  for (let w = 0; w < weeks; w++) {
    // Rótulo de mes en la primera semana que lo contiene (sin pegarse al borde derecho)
    const weekStart = new Date(Math.max(range[0].getTime(), new Date(first.getFullYear(), first.getMonth(), first.getDate() + w * 7).getTime()))
    if (weekStart.getMonth() !== lastMonth && w < weeks - 2) {
      months.push(<text key={'m' + w} x={LEFT + w * (cell + GAP)} y={10} className="axis-label">{fmtMonth.format(weekStart)}</text>)
      lastMonth = weekStart.getMonth()
    }
    for (let d = 0; d < 7; d++) {
      const date = new Date(first.getFullYear(), first.getMonth(), first.getDate() + w * 7 + d)
      if (date < range[0] || date > range[1]) continue
      const key = dayKey(date)
      const info = days.get(key)
      const x = LEFT + w * (cell + GAP)
      const y = TOP + d * (cell + GAP)
      cells.push(
        <rect
          key={key} x={x} y={y} width={cell} height={cell} rx={2}
          className={`cell ${info ? `lvl-${level(info.volume)}` : 'lvl-0'}${hover?.key === key ? ' hovered' : ''}`}
          tabIndex={info ? 0 : -1}
          onPointerEnter={() => setHover({ x: x + cell - scrollX(), y, key, date })}
          onPointerLeave={() => setHover(null)}
          onFocus={() => setHover({ x: x + cell - scrollX(), y, key, date })}
          onBlur={() => setHover(null)}
        />
      )
    }
  }

  const info = hover && days.get(hover.key)
  return (
    <div className="chart">
      <div ref={ref} className="heatmap-scroll">
        {width > 0 && (
          <svg width={svgW} height={svgH} role="img" aria-label="Calendario de entrenamientos">
            {months}
            {DOW.map((l, i) => l && <text key={i} x={0} y={TOP + i * (cell + GAP) + cell / 2} className="axis-label" dominantBaseline="middle">{l}</text>)}
            {cells}
          </svg>
        )}
      </div>
      <div className="heat-legend">
        <span>Descanso</span>
        <span className="cell-swatch lvl-0" />
        <span className="sep" />
        <span>Menos volumen</span>
        {Array.from({ length: LEVELS }, (_, i) => <span key={i} className={`cell-swatch lvl-${i + 1}`} />)}
        <span>Más</span>
      </div>
      {hover && (
        <Tooltip x={Math.min(hover.x, width)} y={hover.y} width={width}>
          <div className="tt-title">{new Intl.DateTimeFormat('es', { weekday: 'long', day: 'numeric', month: 'long' }).format(hover.date)}</div>
          {info ? (
            <>
              <div className="tt-row"><strong>{fmtVolume(info.volume)}</strong><span className="tt-label">{fmtInt(info.sets)} series</span></div>
              <div className="tt-extra">{info.names.join(' · ')}</div>
            </>
          ) : (
            <div className="tt-extra">Descanso</div>
          )}
        </Tooltip>
      )}
    </div>
  )
}
