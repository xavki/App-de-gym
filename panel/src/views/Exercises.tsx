import { useMemo, useState } from 'react'
import { ColumnChart } from '../charts/ColumnChart'
import { LineChart } from '../charts/LineChart'
import {
  exerciseIndex, exerciseSessions, fmtDate, fmtDateShort, fmtKg, fmtNum, fmtVolume, inRange, records,
  type Workout,
} from '../stats'
import type { ExportSet } from '../types'
import { ChartCard, DataTable, StatTile } from '../ui'

const setText = (s: ExportSet) =>
  (s.weightKg > 0 ? `${fmtNum(s.weightKg)}×${s.reps}` : `${s.reps} reps`) + (s.rpe != null ? ` @${fmtNum(s.rpe)}` : '') +
  (s.type === 'FAILURE' ? ' F' : s.type === 'DROP_SET' ? ' D' : '')

/** Progresión por ejercicio (estilo Hevy): 1RM estimado, peso máximo, volumen y récords. */
export function Exercises({ all, range }: { all: Workout[]; range: [Date, Date] }) {
  const inR = useMemo(() => all.filter((w) => inRange(w.date, range)), [all, range])
  const index = useMemo(() => exerciseIndex(inR), [inR])
  const [query, setQuery] = useState('')
  const [picked, setPicked] = useState<string | null>(null)
  const selected = picked && index.some((e) => e.name === picked) ? picked : index[0]?.name ?? null

  const allSessions = useMemo(() => (selected ? exerciseSessions(all, selected) : []), [all, selected])
  const sessions = allSessions.filter((s) => inRange(s.date, range))
  const rec = records(allSessions)
  const hasWeight = allSessions.some((s) => s.topWeight > 0)
  const filtered = index.filter((e) => e.name.toLowerCase().includes(query.toLowerCase()))

  if (!index.length) return <div className="empty big">Sin ejercicios en este rango</div>

  return (
    <div className="split">
      <aside className="card exercise-list">
        <input
          className="search" placeholder="Buscar ejercicio…" value={query}
          onChange={(e) => setQuery(e.target.value)} aria-label="Buscar ejercicio"
        />
        <ul role="listbox" aria-label="Ejercicios">
          {filtered.map((e) => (
            <li key={e.name}>
              <button
                role="option" aria-selected={e.name === selected}
                className={e.name === selected ? 'ex-item active' : 'ex-item'} onClick={() => setPicked(e.name)}
              >
                <span>{e.name}</span>
                <span className="muted small">{e.group} · {e.sessions} sesiones</span>
              </button>
            </li>
          ))}
        </ul>
      </aside>

      {selected && (
        <div className="split-main">
          <h2 className="section-title">{selected}</h2>
          {hasWeight && (
            <div className="stats">
              <StatTile label="1RM estimado" value={rec.bestE1rm ? fmtKg(rec.bestE1rm.value) : '—'} hint={rec.bestE1rm && `${fmtNum(rec.bestE1rm.weight)}×${rec.bestE1rm.reps} · ${fmtDateShort(rec.bestE1rm.date)}`} />
              <StatTile label="Peso máximo" value={rec.topWeight ? fmtKg(rec.topWeight.value) : '—'} hint={rec.topWeight && `×${rec.topWeight.reps} · ${fmtDateShort(rec.topWeight.date)}`} />
              <StatTile label="Mejor serie (volumen)" value={rec.bestSetVolume ? fmtVolume(rec.bestSetVolume.value) : '—'} hint={rec.bestSetVolume && `${fmtNum(rec.bestSetVolume.weight)}×${rec.bestSetVolume.reps}`} />
              <StatTile label="Mejor sesión" value={rec.bestSessionVolume ? fmtVolume(rec.bestSessionVolume.value) : '—'} hint={rec.bestSessionVolume && fmtDateShort(rec.bestSessionVolume.date)} />
            </div>
          )}
          {!hasWeight && rec.maxReps && (
            <div className="stats">
              <StatTile label="Máximo de repeticiones" value={`${rec.maxReps.value}`} hint={fmtDateShort(rec.maxReps.date)} />
            </div>
          )}
          <p className="footnote">Los récords son de todo el historial; los gráficos, del rango elegido.</p>

          <div className="grid">
            {hasWeight && (
              <ChartCard
                wide
                title="Progresión de fuerza"
                subtitle="1RM estimado (Brzycki, series de hasta 12 reps) y peso máximo por sesión"
                table={{
                  columns: ['Fecha', '1RM estimado', 'Peso máximo'],
                  rows: sessions.map((s) => [fmtDate(s.date), s.bestE1rm ? fmtKg(s.bestE1rm) : '—', fmtKg(s.topWeight)]),
                  numeric: [false, true, true],
                }}
              >
                <LineChart
                  format={(v) => `${Math.round(v)} kg`}
                  series={[
                    { id: 'e1rm', label: '1RM estimado', color: 'var(--series-1)', points: sessions.filter((s) => s.bestE1rm > 0).map((s) => ({ x: s.date, y: s.bestE1rm })) },
                    { id: 'top', label: 'Peso máximo', color: 'var(--series-2)', points: sessions.map((s) => ({ x: s.date, y: s.topWeight })) },
                  ]}
                />
              </ChartCard>
            )}

            <ChartCard wide title={hasWeight ? 'Volumen por sesión' : 'Repeticiones por sesión'}>
              <ColumnChart
                data={sessions.map((s) => {
                  const v = hasWeight ? s.volume : s.sets.reduce((a, st) => a + st.reps, 0)
                  return {
                    key: s.workoutId, label: fmtDateShort(s.date), title: fmtDate(s.date), value: v,
                    extra: [s.sets.map(setText).join(', ')],
                  }
                })}
                format={hasWeight ? fmtVolume : (v) => `${v} reps`}
                axisFormat={hasWeight ? (v) => (v >= 1000 ? `${v / 1000} t` : `${v}`) : String}
              />
            </ChartCard>

            <ChartCard wide title="Historial">
              <DataTable
                columns={['Fecha', 'Series', hasWeight ? '1RM est.' : 'Reps', hasWeight ? 'Volumen' : '']}
                numeric={[false, false, true, true]}
                rows={[...sessions].reverse().map((s) => [
                  fmtDate(s.date),
                  s.sets.map(setText).join(', '),
                  hasWeight ? (s.bestE1rm ? fmtKg(s.bestE1rm) : '—') : s.sets.reduce((a, st) => a + st.reps, 0),
                  hasWeight ? fmtVolume(s.volume) : '',
                ])}
              />
            </ChartCard>
          </div>
        </div>
      )}
    </div>
  )
}
