import type { ExportSet, ExportWorkout, GymExport } from './types'

// ─── Fuente de datos ──────────────────────────────────────────────────────────
// El JSON exportado desde la app (archivo) o, si el panel lo sirve tu servidor,
// la misma exportación pedida en vivo a la API (ver "Servidor propio" más abajo).

export const SUPPORTED_SCHEMA = 1
const STORAGE_KEY = 'gymflow-panel:last-export'

export function parseExport(text: string): GymExport {
  let data: unknown
  try {
    data = JSON.parse(text.replace(/^﻿/, '')) // algunos editores añaden BOM al guardar
  } catch {
    throw new Error('El archivo no es un JSON válido.')
  }
  const d = data as Partial<GymExport>
  if (typeof d !== 'object' || d == null || !Array.isArray(d.workouts)) {
    throw new Error('No parece una exportación de GymFlow (falta "workouts").')
  }
  if (d.schemaVersion !== SUPPORTED_SCHEMA) {
    throw new Error(`Versión de exportación ${d.schemaVersion} no soportada (se espera ${SUPPORTED_SCHEMA}).`)
  }
  return {
    ...d,
    routines: d.routines ?? [],
    bodyMeasurements: d.bodyMeasurements ?? [],
    customExercises: d.customExercises ?? [],
    achievements: d.achievements ?? [],
  } as GymExport
}

/** Recuerda el último archivo en este navegador. Si no cabe o no hay almacenamiento, no pasa nada. */
export function saveLocal(text: string) {
  try { localStorage.setItem(STORAGE_KEY, text) } catch { /* sin almacenamiento o sin espacio */ }
}
export function loadLocal(): string | null {
  try { return localStorage.getItem(STORAGE_KEY) } catch { return null }
}
export function clearLocal() {
  try { localStorage.removeItem(STORAGE_KEY) } catch { /* nada */ }
}

// ─── Servidor propio ──────────────────────────────────────────────────────────
// Si el panel lo sirve el servidor GymFlow, lee los datos en vivo. Se vincula una
// vez con el código que muestra la app (Perfil → Vincular panel web); el servidor
// devuelve un token de solo lectura que se recuerda en este navegador.

const TOKEN_KEY = 'gymflow-panel:server-token'

export class UnauthorizedError extends Error {}

export function loadToken(): string | null {
  try { return localStorage.getItem(TOKEN_KEY) } catch { return null }
}
export function saveToken(token: string) {
  try { localStorage.setItem(TOKEN_KEY, token) } catch { /* sin almacenamiento: habrá que vincular otra vez */ }
}
export function clearToken() {
  try { localStorage.removeItem(TOKEN_KEY) } catch { /* nada */ }
}

/** ¿Este panel lo está sirviendo un servidor GymFlow? */
export async function detectServer(): Promise<boolean> {
  try {
    const res = await fetch('/health', { headers: { Accept: 'application/json' } })
    if (!res.ok) return false
    const body = await res.json()
    return body?.app === 'gymflow'
  } catch {
    return false
  }
}

export async function claimCode(code: string): Promise<string> {
  const res = await fetch('/api/pairing/claim', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code, label: navigator.userAgent.slice(0, 100) }),
  })
  if (res.status === 404) throw new Error('Código incorrecto o caducado. Pide uno nuevo en la app.')
  if (res.status === 429) throw new Error('Demasiados intentos. Espera un minuto.')
  if (!res.ok) throw new Error(`El servidor respondió ${res.status}`)
  return (await res.json()).token as string
}

export async function fetchServerExport(token: string): Promise<GymExport> {
  const res = await fetch('/api/export', { headers: { Authorization: `Bearer ${token}` } })
  if (res.status === 401) throw new UnauthorizedError('El acceso de este panel se ha revocado. Vincúlalo de nuevo.')
  if (!res.ok) throw new Error(`El servidor respondió ${res.status}`)
  return parseExport(await res.text())
}

// ─── Datos de ejemplo ─────────────────────────────────────────────────────────
// Seis meses de Push / Pull / Legs con progresión, para probar el panel sin exportar nada.

export function sampleExport(): GymExport {
  let seed = 42
  const rnd = () => ((seed = (seed * 1664525 + 1013904223) % 2 ** 32) / 2 ** 32)
  const uuid = () => 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.floor(rnd() * 16)
    return (c === 'x' ? r : (r & 3) | 8).toString(16)
  })

  const plans: Record<string, [string, string, number, number][]> = {
    // nombre, grupo, peso inicial, kg de progreso por semana
    Push: [['Bench Press', 'Pecho', 60, 0.6], ['Overhead Press', 'Hombros', 35, 0.3], ['Incline Dumbbell Press', 'Pecho', 22, 0.2], ['Triceps Pushdown', 'Tríceps', 20, 0.25]],
    Pull: [['Deadlift', 'Espalda Baja', 100, 1.0], ['Bent Over Barbell Row', 'Espalda', 55, 0.5], ['Pull-Up', 'Espalda', 0, 0], ['Barbell Curl', 'Bíceps', 25, 0.2]],
    Legs: [['Barbell Squat', 'Piernas', 80, 0.9], ['Romanian Deadlift', 'Piernas', 70, 0.6], ['Leg Press', 'Piernas', 140, 1.5], ['Standing Calf Raises', 'Gemelos', 60, 0.5]],
  }
  const order = ['Push', 'Pull', 'Legs']

  const end = new Date()
  end.setHours(19, 0, 0, 0)
  const start = new Date(end.getTime() - 182 * 86_400_000)
  const workouts: ExportWorkout[] = []
  const bodyMeasurements: GymExport['bodyMeasurements'] = []
  let i = 0
  let bw = 78

  for (let d = new Date(start); d <= end; d.setDate(d.getDate() + 1)) {
    const weeks = (d.getTime() - start.getTime()) / (7 * 86_400_000)
    if (d.getDay() === 1) {
      bw += (rnd() - 0.35) * 0.4
      bodyMeasurements.push({
        id: uuid(), measuredAt: new Date(d.getTime() - 11 * 3_600_000).toISOString(), weightKg: +bw.toFixed(1),
        bodyFatPct: +(17 - weeks * 0.08 + rnd() * 0.6).toFixed(1), chestCm: 100, waistCm: +(84 - weeks * 0.06).toFixed(1),
        hipsCm: 98, bicepCm: +(35 + weeks * 0.03).toFixed(1), thighCm: 57,
      })
    }
    // Lunes, miércoles, viernes y a veces sábado; alguna semana se falla
    const trains = [1, 3, 5].includes(d.getDay()) ? rnd() > 0.1 : d.getDay() === 6 && rnd() > 0.7
    if (!trains) continue
    const name = order[i++ % 3]
    const deload = Math.floor(weeks) % 8 === 7
    const exercises = plans[name].map(([ex, group, base, perWeek], p) => {
      const top = Math.round((base + perWeek * weeks * (deload ? 0.8 : 1)) / 2.5) * 2.5
      const sets: ExportSet[] = []
      if (base >= 60) sets.push({ id: uuid(), position: 0, type: 'WARMUP', weightKg: Math.round(top * 0.5 / 2.5) * 2.5, reps: 8, timeSeconds: 0, rpe: null, completed: true })
      for (let s = 0; s < 3; s++) {
        const reps = base === 0 ? 6 + Math.floor(weeks / 4) + Math.floor(rnd() * 3) - s : 8 - s + Math.floor(rnd() * 2)
        sets.push({
          id: uuid(), position: sets.length, type: s === 2 && rnd() > 0.8 ? 'FAILURE' : 'NORMAL',
          weightKg: top, reps, timeSeconds: 0, rpe: rnd() > 0.4 ? 7 + s + (rnd() > 0.5 ? 0.5 : 0) : null, completed: true,
        })
      }
      return { id: uuid(), exerciseId: uuid(), name: ex, mainGroup: group, position: p, notes: '', supersetGroup: null, sets }
    })
    const at = new Date(d)
    at.setHours(18 + Math.floor(rnd() * 3), Math.floor(rnd() * 60))
    workouts.push({
      id: uuid(), routineId: null, name, startedAt: at.toISOString(), durationSeconds: 3000 + Math.floor(rnd() * 1800),
      bodyweightKg: +bw.toFixed(1), notes: '', updatedAt: at.toISOString(), exercises,
    })
  }

  return {
    schemaVersion: 1, exportedAt: end.toISOString(), userId: 'demo',
    units: { weight: 'kg', length: 'cm', time: 's' },
    workouts, routines: [], bodyMeasurements, customExercises: [], achievements: [],
  }
}
