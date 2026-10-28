import type { Tab } from '../navigation'
import { BarList } from '../charts/BarList'
import { CalendarHeatmap } from '../charts/CalendarHeatmap'
import { ColumnChart } from '../charts/ColumnChart'
import { Icon, type IconName } from '../icons'
import {
  dayKey, fmtDate, fmtDuration, fmtInt, fmtVolume, longestStreak, muscleGroupSets, perDay, volumeBuckets,
  type Workout,
} from '../stats'
import { ChartCard, EmptyState, StatTile } from '../ui'

const fmtRange = new Intl.DateTimeFormat('es', { day: 'numeric', month: 'short', year: 'numeric' })
const fmtDayNum = new Intl.DateTimeFormat('es', { day: 'numeric' })
const fmtMonth = new Intl.DateTimeFormat('es', { month: 'short' })

export function Overview({ workouts, range, routines, nutrition, onNavigate }: {
  workouts: Workout[]
  range: [Date, Date]
  routines: number
  nutrition: boolean
  onNavigate: (tab: Tab) => void
}) {
  const volume = workouts.reduce((a, w) => a + w.volume, 0)
  const sets = workouts.reduce((a, w) => a + w.workSets, 0)
  const time = workouts.reduce((a, w) => a + w.durationSeconds, 0)
  const days = perDay(workouts)
  const weeks = Math.max(1, (range[1].getTime() - range[0].getTime()) / (7 * 86_400_000))
  const { unit, buckets } = volumeBuckets(workouts, range)
  const groups = muscleGroupSets(workouts)
  const last = workouts.at(-1)

  const links: { tab: Tab; icon: IconName; title: string; text: string; tone: string }[] = [
    { tab: 'workouts', icon: 'dumbbell', title: 'Entrenamientos', text: `${fmtInt(workouts.length)} en el rango`, tone: 'blue' },
    { tab: 'routines', icon: 'clipboard', title: 'Rutinas', text: routines === 1 ? '1 rutina' : `${fmtInt(routines)} rutinas`, tone: 'violet' },
    { tab: 'exercises', icon: 'chart', title: 'Estadísticas', text: 'Progresión y récords', tone: 'teal' },
    ...(nutrition ? [{ tab: 'nutrition' as Tab, icon: 'apple' as IconName, title: 'Nutrición', text: 'Comidas y macros del día', tone: 'amber' }] : []),
  ]

  return (
    <>
      <section className="hero-card">
        <div className="hero-main">
          <div className="hero-label">Volumen total · {fmtRange.format(range[0])} – {fmtRange.format(range[1])}</div>
          <div className="hero-value">{fmtVolume(volume)}</div>
          <div className="hero-sub">
            {last
              ? <>Último entreno: <strong>{last.name || 'Entreno'}</strong> · {fmtDate(last.date)}</>
              : 'Sin entrenamientos en este rango'}
          </div>
        </div>
        <div className="hero-side" aria-hidden>
          <Icon name="flame" size={56} />
        </div>
      </section>

      <div className="stats">
        <StatTile icon="dumbbell" tone="blue" label="Entrenamientos" value={fmtInt(workouts.length)} hint={`${(workouts.length / weeks).toFixed(1).replace('.', ',')} por semana`} />
        <StatTile icon="layers" tone="teal" label="Series efectivas" value={fmtInt(sets)} hint="Sin calentamientos" />
        <StatTile icon="clock" tone="violet" label="Tiempo entrenando" value={fmtDuration(time)} hint={workouts.length ? `${fmtDuration(time / workouts.length)} de media` : undefined} />
        <StatTile icon="flame" tone="amber" label="Racha más larga" value={`${longestStreak(workouts)} días`} hint="Días seguidos" />
      </div>

      <nav className="quick-links" aria-label="Accesos rápidos">
        {links.map((l) => (
          <button key={l.tab} className="quick-link" onClick={() => onNavigate(l.tab)}>
            <span className={`ql-icon tone-${l.tone}`}><Icon name={l.icon} /></span>
            <span className="ql-text"><strong>{l.title}</strong><span className="muted small">{l.text}</span></span>
            <Icon name="chevronRight" size={18} className="ql-chev" />
          </button>
        ))}
      </nav>

      <div className="grid">
        <ChartCard
          wide icon="chart"
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
          wide icon="calendar"
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
          icon="target"
          title="Series por grupo muscular"
          subtitle="Series efectivas en el rango"
          table={{ columns: ['Grupo', 'Series'], rows: groups.map((g) => [g.label, g.value]), numeric: [false, true] }}
        >
          <BarList data={groups} format={fmtInt} />
        </ChartCard>

        <ChartCard icon="history" title="Actividad reciente" subtitle="Últimos entrenamientos del rango">
          {workouts.length ? (
            <ul className="recent">
              {[...workouts].reverse().slice(0, 6).map((w) => (
                <li key={w.id}>
                  <button className="recent-item" onClick={() => onNavigate('workouts')}>
                    <span className="date-chip"><strong>{fmtDayNum.format(w.date)}</strong><span>{fmtMonth.format(w.date)}</span></span>
                    <span className="recent-text">
                      <strong>{w.name || 'Entreno'}</strong>
                      <span className="muted small">{fmtVolume(w.volume)} · {w.workSets} series · {fmtDuration(w.durationSeconds)}</span>
                    </span>
                    <Icon name="chevronRight" size={18} className="ql-chev" />
                  </button>
                </li>
              ))}
            </ul>
          ) : <EmptyState icon="dumbbell" title="Sin entrenamientos en este rango">Prueba con un rango más largo.</EmptyState>}
        </ChartCard>
      </div>
      <p className="footnote">Calendario: {days.size} días entrenados de {Math.round((range[1].getTime() - range[0].getTime()) / 86_400_000) + 1}. Último: {workouts.length ? dayKey(workouts.at(-1)!.date) : '—'}.</p>
    </>
  )
}
