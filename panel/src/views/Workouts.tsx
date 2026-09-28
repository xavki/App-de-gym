import { useState } from 'react'
import { fmtDate, fmtDuration, fmtKg, fmtNum, fmtVolume, isWorkSet, type Workout } from '../stats'

const TYPE: Record<string, string> = { WARMUP: 'Calentamiento', DROP_SET: 'Drop set', FAILURE: 'Al fallo', NORMAL: '' }

export function Workouts({ workouts }: { workouts: Workout[] }) {
  const [open, setOpen] = useState<string | null>(null)
  if (!workouts.length) return <div className="empty big">Sin entrenamientos en este rango</div>

  return (
    <div className="workout-list">
      {[...workouts].reverse().map((w) => {
        const isOpen = open === w.id
        return (
          <section key={w.id} className="card workout">
            <button className="workout-head" onClick={() => setOpen(isOpen ? null : w.id)} aria-expanded={isOpen}>
              <div>
                <strong>{w.name || 'Entreno'}</strong>
                <div className="muted small">
                  {new Intl.DateTimeFormat('es', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }).format(w.date)}
                </div>
              </div>
              <div className="workout-metrics">
                <span><strong>{fmtVolume(w.volume)}</strong> <span className="muted small">volumen</span></span>
                <span><strong>{w.workSets}</strong> <span className="muted small">series</span></span>
                <span><strong>{fmtDuration(w.durationSeconds)}</strong></span>
                <span className="chev" aria-hidden>{isOpen ? '▾' : '▸'}</span>
              </div>
            </button>
            {isOpen && (
              <div className="workout-body">
                {w.bodyweightKg != null && <p className="muted small">Peso corporal: {fmtKg(w.bodyweightKg)}</p>}
                {w.exercises.map((e) => (
                  <div key={e.id} className="wx">
                    <div className="wx-head">
                      <strong>{e.name}</strong>
                      {e.mainGroup && <span className="muted small"> · {e.mainGroup}</span>}
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
