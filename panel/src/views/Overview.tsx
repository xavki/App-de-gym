import { BarList } from '../charts/BarList'
import { CalendarHeatmap } from '../charts/CalendarHeatmap'
import { ColumnChart } from '../charts/ColumnChart'
import {
  dayKey, fmtDate, fmtDuration, fmtInt, fmtVolume, longestStreak, muscleGroupSets, perDay, volumeBuckets,
  type Workout,
} from '../stats'
import { ChartCard, StatTile } from '../ui'

export function Overview({ workouts, range }: { workouts: Workout[]; range: [Date, Date] }) {
  const volume = workouts.reduce((a, w) => a + w.volume, 0)
  const sets = workouts.reduce((a, w) => a + w.workSets, 0)
  const time = workouts.reduce((a, w) => a + w.durationSeconds, 0)
  const days = perDay(workouts)
  const weeks = Math.max(1, (range[1].getTime() - range[0].getTime()) / (7 * 86_400_000))
  const { unit, buckets } = volumeBuckets(workouts, range)
  const groups = muscleGroupSets(workouts)
  const fmtRange = new Intl.DateTimeFormat('es', { day: 'numeric', month: 'short', year: 'numeric' })

  return (
    <>
      <div className="hero">
        <div className="hero-label">Volumen total · {fmtRange.format(range[0])} – {fmtRange.format(range[1])}</div>
        <div className="hero-value">{fmtVolume(volume)}</div>
      </div>

      <div className="stats">
        <StatTile label="Entrenamientos" value={fmtInt(workouts.length)} hint={`${(workouts.length / weeks).toFixed(1).replace('.', ',')} por semana`} />
        <StatTile label="Series efectivas" value={fmtInt(sets)} hint="Sin calentamientos" />
        <StatTile label="Tiempo entrenando" value={fmtDuration(time)} hint={workouts.length ? `${fmtDuration(time / workouts.length)} de media` : undefined} />
        <StatTile label="Racha más larga" value={`${longestStreak(workouts)} días`} hint="Días seguidos" />
      </div>

      <div className="grid">
        <ChartCard
          wide
          title={unit === 'week' ? 'Volumen por semana' : 'Volumen por mes'}
          subtitle="Peso × repeticiones de las series efectivas"
          table={{
            columns: [unit === 'week' ? 'Semana del' : 'Mes', 'Volumen', 'Entrenos', 'Series'],
            rows: buckets.map((b) => [unit === 'week' ? fmtDate(b.start) : b.label, fmtVolume(b.volume), b.workouts, b.sets]),
            numeric: [false, true, true, true],
          }}
        >
          <ColumnChart
            data={buckets.map((b) => ({
              key: b.start.toISOString(),
              label: b.label,
              title: unit === 'week' ? `Semana del ${fmtDate(b.start)}` : b.label,
              value: b.volume,
              extra: [`${b.workouts} entrenos · ${b.sets} series`],
            }))}
            format={fmtVolume}
            axisFormat={(v) => (v >= 1000 ? `${v / 1000} t` : `${v}`)}
          />
        </ChartCard>

        <ChartCard
          wide
          title="Calendario"
          subtitle="Cada casilla es un día; cuanto más intenso el color, más volumen"
          table={{
            columns: ['Día', 'Entreno', 'Volumen', 'Series'],
            rows: [...days.entries()].reverse().map(([k, d]) => [k, d.names.join(' · '), fmtVolume(d.volume), d.sets]),
            numeric: [false, false, true, true],
          }}
        >
          <CalendarHeatmap range={range} days={days} />
        </ChartCard>

        <ChartCard
          title="Series por grupo muscular"
          subtitle="Series efectivas en el rango"
          table={{ columns: ['Grupo', 'Series'], rows: groups.map((g) => [g.label, g.value]), numeric: [false, true] }}
        >
          <BarList data={groups} format={fmtInt} />
        </ChartCard>

        <ChartCard title="Últimos entrenamientos">
          <ul className="recent">
            {[...workouts].reverse().slice(0, 6).map((w) => (
              <li key={w.id}>
                <div>
                  <strong>{w.name || 'Entreno'}</strong>
                  <span className="muted"> · {fmtDate(w.date)}</span>
                </div>
                <div className="muted small">{fmtVolume(w.volume)} · {w.workSets} series · {fmtDuration(w.durationSeconds)}</div>
              </li>
            ))}
            {!workouts.length && <li className="muted">Sin entrenamientos en este rango</li>}
          </ul>
        </ChartCard>
      </div>
      <p className="footnote">Calendario: {days.size} días entrenados de {Math.round((range[1].getTime() - range[0].getTime()) / 86_400_000) + 1}. Último: {workouts.length ? dayKey(workouts.at(-1)!.date) : '—'}.</p>
    </>
  )
}
