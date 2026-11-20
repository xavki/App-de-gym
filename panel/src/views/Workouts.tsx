import { useState } from 'react'
import { Icon } from '../icons'
import { fmtDate, fmtDuration, fmtKg, fmtNum, fmtVolume, isWorkSet, type Workout } from '../stats'
import { EmptyState } from '../ui'

const TYPE: Record<string, string> = { WARMUP: 'Calentamiento', DROP_SET: 'Drop set', FAILURE: 'Al fallo', NORMAL: '' }
const fmtWeekday = new Intl.DateTimeFormat('es', { weekday: 'long' })
const fmtLong = new Intl.DateTimeFormat('es', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })
const fmtDayNum = new Intl.DateTimeFormat('es', { day: 'numeric' })
const fmtMonth = new Intl.DateTimeFormat('es', { month: 'short' })

export function Workouts({ workouts }: { workouts: Workout[] }) {
  const [open, setOpen] = useState<string | null>(null)
  if (!workouts.length) {
    return <EmptyState icon="dumbbell" title="Sin entrenamientos en este rango">Elige un rango más largo arriba o registra un entreno en la app.</EmptyState>
  }

  return (
    <div className="workout-list">
      {[...workouts].reverse().map((w) => {
        const isOpen = open === w.id
        return (
          <section key={w.id} className={`card workout${isOpen ? ' open' : ''}`}>
            <button className="workout-head" onClick={() => setOpen(isOpen ? null : w.id)} aria-expanded={isOpen}>
              <span className="date-chip big">
                <strong>{fmtDayNum.format(w.date)}</strong>
                <span>{fmtMonth.format(w.date)}</span>
              </span>
              <span className="workout-title">
                <strong>{w.name || 'Entreno'}</strong>
                <span className="muted small capitalize">{fmtWeekday.format(w.date)} · {w.exercises.length} ejercicios</span>
              </span>
              <span className="workout-metrics">
                <span className="metric"><Icon name="layers" size={15} /><strong>{fmtVolume(w.volume)}</strong></span>
                <span className="metric"><Icon name="target" size={15} /><strong>{w.workSets}</strong> series</span>
                <span className="metric"><Icon name="clock" size={15} /><strong>{fmtDuration(w.durationSeconds)}</strong></span>
              </span>
              <Icon name="chevronDown" size={20} className="chev" />
            </button>
            {isOpen && (
              <div className="workout-body">
                <p className="muted small capitalize">
                  {fmtLong.format(w.date)}{w.bodyweightKg != null && <> · Peso corporal: {fmtKg(w.bodyweightKg)}</>}
                </p>
                {w.exercises.map((e) => (
                  <div key={e.id} className="wx">
                    <div className="wx-head">
                      <strong>{e.name}</strong>
                      {e.mainGroup && <span className="badge badge-neutral">{e.mainGroup}</span>}
                    </div>
                    <table className="sets">
                      <thead><tr><th>#</th><th className="num">Kg</th><th className="num">Reps</th><th className="num">RPE</th><th>Tipo</th></tr></thead>
                      <tbody>
                        {e.sets.map((s, i) => (
                          <tr key={s.id} className={isWorkSet(s) ? undefined : 'muted'}>
                            <td>{i + 1}</td>
                            <td className="num">{s.weightKg ? fmtNum(s.weightKg) : '—'}</td>
                            <td className="num">{s.timeSeconds && !s.reps ? fmtDuration(s.timeSeconds) : s.reps}</td>
                            <td className="num">{s.rpe != null ? fmtNum(s.rpe) : '—'}</td>
                            <td>{TYPE[s.type] ?? s.type}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    {e.notes && <p className="muted small">{e.notes}</p>}
                  </div>
                ))}
                <p className="muted small">{fmtDate(w.date)}</p>
              </div>
            )}
          </section>
        )
      })}
    </div>
  )
}
