import { UnauthorizedError } from './data'

// ─── Nutrición (API del servidor) ─────────────────────────────────────────────
// A diferencia del resto del panel, esto lee y escribe en el servidor: PostgreSQL
// es la fuente de verdad y aquí no se guarda nada en el navegador.
// Las fechas son días naturales "YYYY-MM-DD" en la hora local de este navegador.

export interface Macros { kcal: number; proteinG: number; fatG: number; carbsG: number }
export interface Goals { kcal: number; proteinG: number; fatG: number; carbsG: number }
/** Objetivos vigentes en una fecha; effectiveFrom = desde qué día (null si son los iniciales del plan). */
export interface GoalsResponse { goals: Goals; isDefault: boolean; updatedAt?: number | null; effectiveFrom?: string | null }

export type MealCategory = 'BREAKFAST' | 'LUNCH' | 'SNACK' | 'DINNER'
export interface Meal {
  code: string
  category: MealCategory
  name: string
  ingredients: string
  perServing: Macros
  planDays: number[] // 1 = lunes … 7 = domingo
}

export interface NutritionLog {
  id: string
  date: string
  mealCode: string | null // null = comida personalizada
  mealName: string
  servings: number
  perServing: Macros
  total: Macros
  createdAt: number
  updatedAt: number
}

/** Comida fuera del plan: nombre y valores por ración introducidos a mano. */
export interface CustomFood { name: string; kcal: number; proteinG: number; fatG: number; carbsG: number }

/** Nombre corto de un registro: el código del plan o, si es personalizada, su nombre. */
export const logLabel = (l: NutritionLog) => l.mealCode ?? l.mealName

export interface DaySummary { date: string; consumed: Macros; goals: Goals; logs: number }

export const CATEGORIES: { key: MealCategory; label: string }[] = [
  { key: 'BREAKFAST', label: 'Desayuno' },
  { key: 'LUNCH', label: 'Comida' },
  { key: 'SNACK', label: 'Merienda' },
  { key: 'DINNER', label: 'Cena' },
]

export const ZERO: Macros = { kcal: 0, proteinG: 0, fatG: 0, carbsG: 0 }

const round1 = (n: number) => Math.round(n * 10) / 10
/** Suma de los totales de los registros, redondeada como en el servidor. */
export function sumMacros(logs: NutritionLog[]): Macros {
  const t = logs.reduce((a, l) => ({
    kcal: a.kcal + l.total.kcal, proteinG: a.proteinG + l.total.proteinG,
    fatG: a.fatG + l.total.fatG, carbsG: a.carbsG + l.total.carbsG,
  }), ZERO)
  return { kcal: round1(t.kcal), proteinG: round1(t.proteinG), fatG: round1(t.fatG), carbsG: round1(t.carbsG) }
}

// ─── Fechas locales ──────────────────────────────────────────────────────────

const pad = (n: number) => String(n).padStart(2, '0')
export const isoDay = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
export const parseDay = (s: string) => { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d) }
export const addDays = (s: string, n: number) => { const d = parseDay(s); d.setDate(d.getDate() + n); return isoDay(d) }
/** Día de la semana ISO: 1 = lunes … 7 = domingo. */
export const isoWeekday = (s: string) => ((parseDay(s).getDay() + 6) % 7) + 1

/** UUID v4 para cada registro: si la petición se reintenta, el servidor no la duplica. */
export function newId(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  const b = crypto.getRandomValues(new Uint8Array(16))
  b[6] = (b[6] & 0x0f) | 0x40
  b[8] = (b[8] & 0x3f) | 0x80
  const h = [...b].map((x) => x.toString(16).padStart(2, '0')).join('')
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
}

// ─── Peticiones ──────────────────────────────────────────────────────────────

/** Error con un mensaje apto para enseñar al usuario. */
export class NutritionError extends Error {}

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms))

/**
 * Petición autenticada. Los fallos de red y los 5xx se reintentan (como mucho 2 veces):
 * es seguro porque las escrituras son idempotentes (mismo id, mismas raciones, borrado repetible).
 */
async function api<T>(token: string, path: string, init: RequestInit = {}): Promise<T> {
  let lastError: Error = new NutritionError('No se pudo conectar con el servidor')
  for (let attempt = 0; attempt < 3; attempt++) {
    if (attempt) await wait(400 * attempt)
    let res: Response
    try {
      res = await fetch(path, {
        ...init,
        headers: { Authorization: `Bearer ${token}`, ...(init.body ? { 'Content-Type': 'application/json' } : {}) },
      })
    } catch {
      lastError = new NutritionError('Sin conexión con el servidor. Se ha reintentado; prueba otra vez.')
      continue
    }
    if (res.status === 401) throw new UnauthorizedError('El acceso de este panel se ha revocado. Vincúlalo de nuevo.')
    if (res.status >= 500) { lastError = new NutritionError(`El servidor ha fallado (${res.status}). Prueba otra vez.`); continue }
    if (!res.ok) {
      const body = await res.json().catch(() => null) as { error?: string } | null
      throw new NutritionError(body?.error ?? `El servidor respondió ${res.status}`)
    }
    return (res.status === 204 ? undefined : await res.json()) as T
  }
  throw lastError
}

export const fetchMeals = (token: string) => api<{ meals: Meal[] }>(token, '/api/nutrition/meals').then((r) => r.meals)
export const fetchGoals = (token: string, date?: string) =>
  api<GoalsResponse>(token, date ? `/api/nutrition/goals?date=${date}` : '/api/nutrition/goals')
export const saveGoals = (token: string, goals: Goals) =>
  api<GoalsResponse>(token, '/api/nutrition/goals', { method: 'PUT', body: JSON.stringify(goals) })
export const fetchLogs = (token: string, date: string) =>
  api<{ logs: NutritionLog[] }>(token, `/api/nutrition/logs?date=${date}`).then((r) => r.logs)
export const createLog = (token: string, body: { id: string; date: string; servings: number } & ({ mealCode: string } | { custom: CustomFood })) =>
  api<NutritionLog>(token, '/api/nutrition/logs', { method: 'POST', body: JSON.stringify(body) })
export const updateLog = (token: string, id: string, servings: number) =>
  api<NutritionLog>(token, `/api/nutrition/logs/${id}`, { method: 'PATCH', body: JSON.stringify({ servings }) })
export const deleteLog = (token: string, id: string) => api<void>(token, `/api/nutrition/logs/${id}`, { method: 'DELETE' })
export const fetchDays = (token: string, from: string, to: string) =>
  api<{ days: DaySummary[] }>(token, `/api/nutrition/days?from=${from}&to=${to}`).then((r) => r.days)
