import { Icon } from '../icons'
import { fmtDuration, fmtInt, fmtNum } from '../stats'
import type { ExportRoutine } from '../types'
import { Badge, EmptyState } from '../ui'

// Rutinas tal como llegan en la exportación (/api/export o el JSON): solo se muestran.

type RoutineSet = ExportRoutine['exercises'][number]['sets'][number]

const TYPE: Record<string, string> = { WARMUP: 'Calent.', DROP_SET: 'Drop', FAILURE: 'Fallo' }

/** "60 kg × 8", "8 reps", "45 s"… según lo que tenga la serie objetivo. */
function setText(s: RoutineSet) {
  const t = s.targetTimeSeconds
  const effort = s.targetReps > 0 ? `${s.targetReps} reps` : t > 0 ? (t < 60 ? `${t} s` : fmtDuration(t)) : '—'
  return s.targetWeightKg > 0 ? `${fmtNum(s.targetWeightKg)} kg × ${s.targetReps > 0 ? s.targetReps : effort}` : effort
}

export function Routines({ routines }: { routines: ExportRoutine[] }) {
  if (!routines.length) {
    return <EmptyState icon="clipboard" title="Aún no hay rutinas">Crea tus rutinas en la app y sincroniza: aparecerán aquí.</EmptyState>
  }

  return (
    <div className="routine-grid">
      {routines.map((r) => {
        const exercises = [...r.exercises].sort((a, b) => a.position - b.position)
        const sets = exercises.reduce((a, e) => a + e.sets.length, 0)
        const groups = [...new Set(exercises.map((e) => e.mainGroup).filter(Boolean))] as string[]
        return (
          <section key={r.id} className="card routine">
            <header className="routine-head">
              <span className="routine-icon"><Icon name="clipboard" size={22} /></span>
              <div className="routine-title">
                <h2>{r.name || 'Rutina'}</h2>
                <p className="subtitle">{fmtInt(exercises.length)} ejercicios · {fmtInt(sets)} series</p>
              </div>
            </header>
            {groups.length > 0 && <div className="chips">{groups.map((g) => <Badge key={g}>{g}</Badge>)}</div>}
            <ol className="routine-exercises">
              {exercises.map((e, i) => (
                <li key={e.id}>
                  <span className="rx-num">{i + 1}</span>
                  <div className="rx-body">
                    <strong>{e.name}</strong>
                    <div className="set-chips">
                      {[...e.sets].sort((a, b) => a.position - b.position).map((s) => (
                        <span key={s.position} className={`set-chip${s.type === 'WARMUP' ? ' warmup' : ''}`}>
                          {setText(s)}{TYPE[s.type] && <em>{TYPE[s.type]}</em>}
                        </span>
                      ))}
                      {!e.sets.length && <span className="muted small">Sin series definidas</span>}
                    </div>
                    {e.notes && <p className="muted small">{e.notes}</p>}
                  </div>
                </li>
              ))}
            </ol>
          </section>
        )
      })}
    </div>
  )
}
