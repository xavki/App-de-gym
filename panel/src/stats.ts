import type { ExportSet, ExportWorkout, GymExport } from './types'

// ─── Reglas de cálculo (las mismas que la app) ────────────────────────────────
// • Las series de calentamiento no cuentan para volumen, series ni récords.
// • 1RM estimado con Brzycki, solo fiable hasta 12 repeticiones.

export const DAY = 86_400_000

export const isWorkSet = (s: ExportSet) => s.type !== 'WARMUP' && (s.weightKg > 0 || s.reps > 0 || s.timeSeconds > 0)
export const setVolume = (s: ExportSet) => (isWorkSet(s) ? s.weightKg * s.reps : 0)
export const e1rm = (weight: number, reps: number) =>
  weight > 0 && reps >= 1 && reps <= 12 ? weight * (36 / (37 - reps)) : 0

export interface Workout extends Omit<ExportWorkout, 'startedAt'> {
  date: Date
  volume: number
  workSets: number
}

export function prepare(data: GymExport): Workout[] {
  return data.workouts
    .map((w) => {
      const sets = w.exercises.flatMap((e) => e.sets)
      return {
        ...w,
        date: new Date(w.startedAt),
        volume: sets.reduce((a, s) => a + setVolume(s), 0),
        workSets: sets.filter(isWorkSet).length,
      }
    })
    .sort((a, b) => a.date.getTime() - b.date.getTime())
}

// ─── Rango de fechas ──────────────────────────────────────────────────────────

export type RangeKey = '30d' | '90d' | '180d' | '365d' | 'all'
export const RANGES: { key: RangeKey; label: string; days: number | null }[] = [
  { key: '30d', label: '30 días', days: 30 },
  { key: '90d', label: '90 días', days: 90 },
  { key: '180d', label: '6 meses', days: 180 },
  { key: '365d', label: '1 año', days: 365 },
  { key: 'all', label: 'Todo', days: null },
]

/** [inicio, fin] del rango. "Hoy" es el día del último entreno o de la exportación. */
export function rangeBounds(key: RangeKey, workouts: Workout[], exportedAt: string): [Date, Date] {
  const end = endOfDay(new Date(Math.max(new Date(exportedAt).getTime() || 0, workouts.at(-1)?.date.getTime() ?? 0)))
  const days = RANGES.find((r) => r.key === key)!.days
  if (days == null) {
    const first = workouts[0]?.date ?? end
    return [startOfDay(first), end]
  }
  return [startOfDay(new Date(end.getTime() - (days - 1) * DAY)), end]
}

export const inRange = (d: Date, [a, b]: [Date, Date]) => d >= a && d <= b

// ─── Utilidades de fecha (hora local) ────────────────────────────────────────

export const startOfDay = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate())
export const endOfDay = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate(), 23, 59, 59, 999)
export const dayKey = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
/** Lunes de la semana de d. */
export const startOfWeek = (d: Date) => {
  const s = startOfDay(d)
  s.setDate(s.getDate() - ((s.getDay() + 6) % 7))
  return s
}

// ─── Agregados ───────────────────────────────────────────────────────────────

export interface Bucket { start: Date; label: string; volume: number; workouts: number; sets: number }

/** Volumen por semana (o por mes si el rango es largo, para que las columnas no sean hilos). */
export function volumeBuckets(workouts: Workout[], range: [Date, Date]): { unit: 'week' | 'month'; buckets: Bucket[] } {
  const weeks = Math.ceil((range[1].getTime() - range[0].getTime()) / (7 * DAY))
  const unit = weeks > 26 ? 'month' : 'week'
  const buckets: Bucket[] = []
  const fmtWeek = new Intl.DateTimeFormat('es', { day: 'numeric', month: 'short' })
  const fmtMonth = new Intl.DateTimeFormat('es', { month: 'short', year: '2-digit' })

  let cursor = unit === 'week' ? startOfWeek(range[0]) : new Date(range[0].getFullYear(), range[0].getMonth(), 1)
  while (cursor <= range[1]) {
    buckets.push({ start: new Date(cursor), label: unit === 'week' ? fmtWeek.format(cursor) : fmtMonth.format(cursor), volume: 0, workouts: 0, sets: 0 })
    if (unit === 'week') cursor.setDate(cursor.getDate() + 7)
    else cursor = new Date(cursor.getFullYear(), cursor.getMonth() + 1, 1)
  }
  for (const w of workouts) {
    const b = [...buckets].reverse().find((b) => b.start <= w.date)
    if (!b) continue
    b.volume += w.volume
    b.workouts += 1
    b.sets += w.workSets
  }
  return { unit, buckets }
}

export function perDay(workouts: Workout[]) {
  const m = new Map<string, { volume: number; sets: number; names: string[] }>()
  for (const w of workouts) {
    const k = dayKey(w.date)
    const v = m.get(k) ?? { volume: 0, sets: 0, names: [] }
    v.volume += w.volume
    v.sets += w.workSets
    v.names.push(w.name)
    m.set(k, v)
  }
  return m
}

/** Racha más larga de días seguidos entrenando. */
export function longestStreak(workouts: Workout[]) {
  const days = [...new Set(workouts.map((w) => startOfDay(w.date).getTime()))].sort((a, b) => a - b)
  let best = 0, cur = 0, prev = -Infinity
  for (const d of days) {
    cur = Math.round((d - prev) / DAY) === 1 ? cur + 1 : 1
    best = Math.max(best, cur)
    prev = d
  }
  return best
}

export function muscleGroupSets(workouts: Workout[]) {
  const m = new Map<string, number>()
  for (const w of workouts)
    for (const e of w.exercises) {
      const n = e.sets.filter(isWorkSet).length
      if (n) m.set(e.mainGroup || 'Sin grupo', (m.get(e.mainGroup || 'Sin grupo') ?? 0) + n)
    }
  return [...m.entries()].map(([label, value]) => ({ label, value })).sort((a, b) => b.value - a.value)
}

// ─── Por ejercicio ───────────────────────────────────────────────────────────

export interface ExerciseSession {
  workoutId: string
  date: Date
  bestE1rm: number
  topWeight: number
  volume: number
  sets: ExportSet[]
}

export function exerciseIndex(workouts: Workout[]) {
  const m = new Map<string, { name: string; group: string; sessions: number; lastDate: Date }>()
  for (const w of workouts)
    for (const e of w.exercises) {
      if (!e.sets.some(isWorkSet)) continue
      const v = m.get(e.name) ?? { name: e.name, group: e.mainGroup || 'Sin grupo', sessions: 0, lastDate: w.date }
      v.sessions += 1
      v.lastDate = w.date
      m.set(e.name, v)
    }
  return [...m.values()].sort((a, b) => b.sessions - a.sessions || a.name.localeCompare(b.name))
}

export function exerciseSessions(workouts: Workout[], name: string): ExerciseSession[] {
  const out: ExerciseSession[] = []
  for (const w of workouts) {
    const sets = w.exercises.filter((e) => e.name === name).flatMap((e) => e.sets).filter(isWorkSet)
    if (!sets.length) continue
    out.push({
      workoutId: w.id,
      date: w.date,
      bestE1rm: Math.max(0, ...sets.map((s) => e1rm(s.weightKg, s.reps))),
      topWeight: Math.max(0, ...sets.map((s) => s.weightKg)),
      volume: sets.reduce((a, s) => a + setVolume(s), 0),
      sets,
    })
  }
  return out
}

export interface Records {
  topWeight?: { value: number; reps: number; date: Date }
  bestE1rm?: { value: number; weight: number; reps: number; date: Date }
  bestSetVolume?: { value: number; weight: number; reps: number; date: Date }
  maxReps?: { value: number; weight: number; date: Date }
  bestSessionVolume?: { value: number; date: Date }
}

/** Récords de siempre (no dependen del rango: un PR es un PR). */
export function records(sessions: ExerciseSession[]): Records {
  const r: Records = {}
  for (const s of sessions) {
    for (const st of s.sets) {
      if (st.weightKg > (r.topWeight?.value ?? 0)) r.topWeight = { value: st.weightKg, reps: st.reps, date: s.date }
      const e = e1rm(st.weightKg, st.reps)
      if (e > (r.bestE1rm?.value ?? 0)) r.bestE1rm = { value: e, weight: st.weightKg, reps: st.reps, date: s.date }
      const v = st.weightKg * st.reps
      if (v > (r.bestSetVolume?.value ?? 0)) r.bestSetVolume = { value: v, weight: st.weightKg, reps: st.reps, date: s.date }
      if (st.reps > (r.maxReps?.value ?? 0)) r.maxReps = { value: st.reps, weight: st.weightKg, date: s.date }
    }
    if (s.volume > (r.bestSessionVolume?.value ?? 0)) r.bestSessionVolume = { value: s.volume, date: s.date }
  }
  return r
}

// ─── Formato ─────────────────────────────────────────────────────────────────

const nf0 = new Intl.NumberFormat('es', { maximumFractionDigits: 0 })
const nf1 = new Intl.NumberFormat('es', { maximumFractionDigits: 1 })
export const fmtInt = (n: number) => nf0.format(n)
/** Número con coma decimal española y sin ceros de relleno: 27,5 · 80 · 8,5 */
export const fmtNum = (n: number) => nf1.format(n)
export const fmtKg = (n: number) => `${nf1.format(n)} kg`
/** 12.400 kg → "12,4 t"; por debajo de 10 t se muestran kg. */
export const fmtVolume = (kg: number) => (kg >= 10_000 ? `${nf1.format(kg / 1000)} t` : `${nf0.format(kg)} kg`)
export const fmtDuration = (s: number) => {
  const h = Math.floor(s / 3600), m = Math.round((s % 3600) / 60)
  return h ? `${h} h ${m} min` : `${m} min`
}
export const fmtDate = (d: Date) => new Intl.DateTimeFormat('es', { day: 'numeric', month: 'short', year: 'numeric' }).format(d)
export const fmtDateShort = (d: Date) => new Intl.DateTimeFormat('es', { day: 'numeric', month: 'short' }).format(d)
