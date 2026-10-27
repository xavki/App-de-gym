import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { UnauthorizedError } from '../data'
import {
  CATEGORIES, addDays, createLog, deleteLog, fetchDays, fetchGoals, fetchLogs, fetchMeals, isoDay, isoWeekday, logLabel, newId,
  parseDay, saveGoals, sumMacros, updateLog, type CustomFood, type DaySummary, type Goals, type Meal, type NutritionLog,
} from '../nutrition'
import { fmtInt, fmtNum } from '../stats'

const SERVING_STEP = 0.25
const fmtDay = (s: string) => new Intl.DateTimeFormat('es', { weekday: 'long', day: 'numeric', month: 'long' }).format(parseDay(s))
const fmtDayShort = (s: string) => new Intl.DateTimeFormat('es', { weekday: 'short', day: 'numeric', month: 'short' }).format(parseDay(s))
const fmtServings = (n: number) => (n === 1 ? '1 ración' : `${fmtNum(n)} raciones`)

interface Toast { text: string; undo?: () => void }

/**
 * Pestaña Nutrición: objetivos del día, registro rápido de las comidas del plan,
 * corrección de raciones, historial y objetivos. Todo se lee y guarda en el servidor.
 */
export function Nutrition({ token, onUnauthorized }: { token: string; onUnauthorized: (message: string) => void }) {
  const [today] = useState(() => isoDay(new Date()))
  const [date, setDate] = useState(today)
  const [meals, setMeals] = useState<Meal[] | null>(null)
  const [goals, setGoals] = useState<Goals | null>(null)
  const [goalsAreDefault, setGoalsAreDefault] = useState(false)
  // Registros y objetivos vigentes del día [loaded.date]; mientras se carga otro día, logs = null
  const [loaded, setLoaded] = useState<{ date: string; logs: NutritionLog[]; goals: Goals } | null>(null)
  const logs = loaded?.date === date ? loaded.logs : null
  const dayGoals = loaded?.date === date ? loaded.goals : null
  const setLogs = (f: (l: NutritionLog[] | null) => NutritionLog[]) =>
    setLoaded((cur) => (cur && cur.date === date ? { ...cur, logs: f(cur.logs) } : cur))
  const [days, setDays] = useState<DaySummary[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<Set<string>>(new Set()) // códigos de comida o ids con una petición en curso
  const [toast, setToast] = useState<Toast | null>(null)
  const toastTimer = useRef<number | undefined>(undefined)

  const fail = useCallback((e: unknown) => {
    if (e instanceof UnauthorizedError) return onUnauthorized(e.message)
    setError((e as Error).message)
  }, [onUnauthorized])

  const notify = (t: Toast) => {
    setToast(t)
    window.clearTimeout(toastTimer.current)
    toastTimer.current = window.setTimeout(() => setToast(null), 6000)
  }
  useEffect(() => () => window.clearTimeout(toastTimer.current), [])

  const withBusy = async (key: string, fn: () => Promise<void>) => {
    if (busy.has(key)) return // evita el doble clic
    setBusy((s) => new Set(s).add(key))
    try { await fn(); setError(null) } catch (e) { fail(e) } finally {
      setBusy((s) => { const n = new Set(s); n.delete(key); return n })
    }
  }

  const refreshDays = useCallback(() =>
    fetchDays(token, addDays(today, -29), today).then(setDays).catch(fail), [token, today, fail])

  useEffect(() => {
    Promise.all([fetchMeals(token), fetchGoals(token)])
      .then(([m, g]) => { setMeals(m); setGoals(g.goals); setGoalsAreDefault(g.isDefault) })
      .catch(fail)
    refreshDays()
  }, [token, fail, refreshDays])

  useEffect(() => {
    let current = true
    Promise.all([fetchLogs(token, date), fetchGoals(token, date)])
      .then(([l, g]) => { if (current) setLoaded({ date, logs: l, goals: g.goals }) })
      .catch(fail)
    return () => { current = false }
  }, [token, date, fail])

  const consumed = useMemo(() => sumMacros(logs ?? []), [logs])
  const weekday = isoWeekday(date)
  const byMeal = useMemo(() => {
    const m = new Map<string, NutritionLog[]>()
    for (const l of logs ?? []) if (l.mealCode) m.set(l.mealCode, [...(m.get(l.mealCode) ?? []), l])
    return m
  }, [logs])

  // ─── Acciones ──────────────────────────────────────────────────────────────

  const add = (meal: Meal, servings = 1) => withBusy(meal.code, async () => {
    const log = await createLog(token, { id: newId(), mealCode: meal.code, date, servings })
    setLogs((l) => [...(l ?? []).filter((x) => x.id !== log.id), log])
    notify({ text: `${meal.code} registrada: ${fmtInt(log.total.kcal)} kcal`, undo: () => remove(log, false) })
    refreshDays()
  })

  /** Comida personalizada: valores por ración escritos por el usuario. */
  const addCustom = (food: CustomFood, servings: number) => withBusy('custom', async () => {
    const log = await createLog(token, { id: newId(), custom: food, date, servings })
    setLogs((l) => [...(l ?? []).filter((x) => x.id !== log.id), log])
    notify({ text: `${log.mealName} registrada: ${fmtInt(log.total.kcal)} kcal`, undo: () => remove(log, false) })
    refreshDays()
  })

  const changeServings = (log: NutritionLog, servings: number) => {
    if (servings <= 0 || servings > 10) return
    return withBusy(log.id, async () => {
      const updated = await updateLog(token, log.id, servings)
      setLogs((l) => (l ?? []).map((x) => (x.id === updated.id ? updated : x)))
      refreshDays()
    })
  }

  /** Borra el registro; el aviso permite volver a crearlo igual (con un id nuevo). */
  const remove = (log: NutritionLog, offerUndo = true) => withBusy(log.id, async () => {
    await deleteLog(token, log.id)
    setLogs((l) => (l ?? []).filter((x) => x.id !== log.id))
    refreshDays()
    if (!offerUndo) { setToast(null); return }
    notify({
      text: `${logLabel(log)} eliminada`,
      undo: () => withBusy(log.mealCode ?? 'custom', async () => {
        const what = log.mealCode
          ? { mealCode: log.mealCode }
          : { custom: { name: log.mealName, ...log.perServing } }
        const again = await createLog(token, { id: newId(), ...what, date: log.date, servings: log.servings })
        if (again.date === date) setLogs((l) => [...(l ?? []), again])
        setToast(null)
        refreshDays()
      }),
    })
  })

  const storeGoals = async (g: Goals) => {
    try {
      const saved = await saveGoals(token, g)
      setGoals(saved.goals)
      setGoalsAreDefault(saved.isDefault)
      // Valen desde hoy: el resumen solo cambia si se está viendo hoy (los días anteriores conservan los suyos)
      if (date >= today) setLoaded((cur) => (cur && cur.date === date ? { ...cur, goals: saved.goals } : cur))
      setError(null)
      notify({ text: 'Objetivos guardados' })
      refreshDays()
      return true
    } catch (e) { fail(e); return false }
  }

  // ─── Vista ─────────────────────────────────────────────────────────────────

  const shownGoals = dayGoals ?? goals

  if (!meals || !goals || !shownGoals) {
    return error ? <div className="error" role="alert">{error}</div> : <div className="empty big" aria-busy="true">Cargando nutrición…</div>
  }

  return (
    <div className="nutrition">
      {error && <div className="error" role="alert">{error}</div>}

      <div className="day-picker" role="group" aria-label="Día">
        <button className="btn ghost icon" onClick={() => setDate(addDays(date, -1))} aria-label="Día anterior">‹</button>
        <label className="day-label">
          <span className="day-name">{date === today ? 'Hoy' : fmtDay(date)}</span>
          <input type="date" value={date} max={today} onChange={(e) => e.target.value && setDate(e.target.value)} aria-label="Elegir fecha" />
        </label>
        <button className="btn ghost icon" onClick={() => setDate(addDays(date, 1))} disabled={date >= today} aria-label="Día siguiente">›</button>
        {date !== today && <button className="btn ghost" onClick={() => setDate(today)}>Hoy</button>}
      </div>

      <section className="card" aria-labelledby="sum-title">
        <header className="card-head">
          <div>
            <h2 id="sum-title">{date === today ? 'Resumen de hoy' : `Resumen del ${fmtDayShort(date)}`}</h2>
            <p className="subtitle">
              {logs ? `${logs.length} ${logs.length === 1 ? 'comida registrada' : 'comidas registradas'}` : 'Cargando…'}
              {date !== today && dayGoals && ' · objetivos vigentes ese día'}
            </p>
          </div>
        </header>
        <div className="macro-bars">
          <MacroBar label="Calorías" unit="kcal" value={consumed.kcal} goal={shownGoals.kcal} big />
          <MacroBar label="Proteínas" unit="g" value={consumed.proteinG} goal={shownGoals.proteinG} />
          <MacroBar label="Grasas" unit="g" value={consumed.fatG} goal={shownGoals.fatG} />
          <MacroBar label="Carbohidratos" unit="g" value={consumed.carbsG} goal={shownGoals.carbsG} />
        </div>
      </section>

      {CATEGORIES.map((cat) => (
        <section key={cat.key} className="meal-group" aria-labelledby={`cat-${cat.key}`}>
          <h2 id={`cat-${cat.key}`} className="section-title">{cat.label}</h2>
          <div className="meal-grid">
            {meals.filter((m) => m.category === cat.key).map((meal) => (
              <MealCard
                key={meal.code} meal={meal} planned={meal.planDays.includes(weekday)}
                logged={byMeal.get(meal.code) ?? []} busy={busy.has(meal.code)} loading={!logs}
                onAdd={() => add(meal)}
                onUndo={(log) => remove(log)}
              />
            ))}
          </div>
        </section>
      ))}

      <CustomMealForm busy={busy.has('custom')} disabled={!logs} onAdd={addCustom} />

      <section className="card" aria-labelledby="day-logs">
        <header className="card-head">
          <div>
            <h2 id="day-logs">Registrado {date === today ? 'hoy' : `el ${fmtDayShort(date)}`}</h2>
            <p className="subtitle">Ajusta las raciones o elimina lo que no hayas comido</p>
          </div>
        </header>
        {!logs ? <div className="empty">Cargando…</div> : logs.length === 0 ? <div className="empty">Nada registrado este día.</div> : (
          <ul className="log-list">
            {logs.map((log) => (
              <li key={log.id} className={busy.has(log.id) ? 'pending' : undefined}>
                <div className="log-main">
                  <span className={log.mealCode ? 'code-badge' : 'code-badge custom'}>{log.mealCode ?? 'Otra'}</span>
                  <span className="log-name">{log.mealName}</span>
                </div>
                <div className="log-actions">
                  <div className="stepper" role="group" aria-label={`Raciones de ${logLabel(log)}`}>
                    <button onClick={() => changeServings(log, log.servings - SERVING_STEP)} disabled={log.servings <= SERVING_STEP || busy.has(log.id)} aria-label="Menos">−</button>
                    <span aria-live="polite">{fmtNum(log.servings)}×</span>
                    <button onClick={() => changeServings(log, log.servings + SERVING_STEP)} disabled={log.servings >= 10 || busy.has(log.id)} aria-label="Más">+</button>
                  </div>
                  <span className="log-kcal">{fmtInt(log.total.kcal)} kcal</span>
                  <button className="btn ghost danger" onClick={() => remove(log)} disabled={busy.has(log.id)}>Eliminar</button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      <History days={days} selected={date} onSelect={setDate} />

      <GoalsForm key={JSON.stringify(goals)} goals={goals} isDefault={goalsAreDefault} onSave={storeGoals} />

      {toast && (
        <div className="toast" role="status">
          <span>✓ {toast.text}</span>
          {toast.undo && <button className="link-btn" onClick={toast.undo}>Deshacer</button>}
        </div>
      )}
    </div>
  )
}

function MacroBar({ label, unit, value, goal, big = false }: { label: string; unit: string; value: number; goal: number; big?: boolean }) {
  const pct = goal > 0 ? (value / goal) * 100 : 0
  const over = value - goal
  const fmt = unit === 'kcal' ? fmtInt : fmtNum
  return (
    <div className={`macro${big ? ' big' : ''}${over > 0 ? ' over' : ''}`}>
      <div className="macro-head">
        <span className="macro-label">{label}</span>
        <span className="macro-value"><strong>{fmt(value)}</strong> / {fmt(goal)} {unit}</span>
      </div>
      <div className="bar" role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={goal} aria-valuenow={value}
        aria-valuetext={`${fmt(value)} de ${fmt(goal)} ${unit}`}>
        <div className="bar-fill" style={{ width: `${Math.min(pct, 100)}%` }} />
      </div>
      <div className="macro-hint">
        {over > 0 ? `+${fmt(over)} ${unit} por encima del objetivo (${fmtInt(pct)} %)` : `Quedan ${fmt(-over)} ${unit} · ${fmtInt(pct)} %`}
      </div>
    </div>
  )
}

function MealCard({ meal, planned, logged, busy, loading, onAdd, onUndo }: {
  meal: Meal; planned: boolean; logged: NutritionLog[]; busy: boolean; loading: boolean
  onAdd: () => void; onUndo: (log: NutritionLog) => void
}) {
  const done = logged.length > 0
  const servings = logged.reduce((a, l) => a + l.servings, 0)
  const m = meal.perServing
  return (
    <article className={`meal-card${done ? ' done' : ''}`}>
      <header className="meal-head">
        <span className="code-badge">{meal.code}</span>
        <h3>{meal.name}</h3>
        {planned && <span className="plan-badge" title="Lo propone el menú semanal para este día">Plan del día</span>}
      </header>
      <p className="ingredients">{meal.ingredients}</p>
      <dl className="meal-macros">
        <div><dt>kcal</dt><dd>{fmtInt(m.kcal)}</dd></div>
        <div><dt>Prot.</dt><dd>{fmtNum(m.proteinG)} g</dd></div>
        <div><dt>Grasa</dt><dd>{fmtNum(m.fatG)} g</dd></div>
        <div><dt>Carb.</dt><dd>{fmtNum(m.carbsG)} g</dd></div>
      </dl>
      {done && <p className="meal-status" aria-live="polite">✓ Registrada · {fmtServings(servings)}</p>}
      <div className="meal-actions">
        <button className={done ? 'btn ghost big-btn' : 'btn big-btn'} onClick={onAdd} disabled={busy || loading} aria-busy={busy}>
          {busy ? 'Guardando…' : done ? 'Registrar otra vez' : 'Marcar como realizada'}
        </button>
        {done && (
          <button className="btn ghost" onClick={() => onUndo(logged[logged.length - 1])} disabled={busy}>Deshacer</button>
        )}
      </div>
    </article>
  )
}

function History({ days, selected, onSelect }: { days: DaySummary[]; selected: string; onSelect: (d: string) => void }) {
  return (
    <section className="card" aria-labelledby="hist-title">
      <header className="card-head">
        <div>
          <h2 id="hist-title">Historial</h2>
          <p className="subtitle">Últimos 30 días con registros · pulsa un día para verlo o corregirlo</p>
        </div>
      </header>
      {days.length === 0 ? <div className="empty">Aún no hay días registrados.</div> : (
        <div className="table-scroll">
          <table>
            <thead>
              <tr><th>Día</th><th className="num">kcal</th><th className="num">Prot.</th><th className="num">Grasa</th><th className="num">Carb.</th></tr>
            </thead>
            <tbody>
              {days.map((d) => (
                <tr key={d.date} className={`clickable${d.date === selected ? ' selected' : ''}`} onClick={() => onSelect(d.date)}
                  tabIndex={0} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelect(d.date) } }}
                  aria-current={d.date === selected ? 'date' : undefined}>
                  <td>{fmtDayShort(d.date)}</td>
                  <td className={`num${d.consumed.kcal > d.goals.kcal ? ' over-text' : ''}`}>{fmtInt(d.consumed.kcal)} / {fmtInt(d.goals.kcal)}</td>
                  <td className="num">{fmtNum(d.consumed.proteinG)} g</td>
                  <td className="num">{fmtNum(d.consumed.fatG)} g</td>
                  <td className="num">{fmtNum(d.consumed.carbsG)} g</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

function GoalsForm({ goals, isDefault, onSave }: { goals: Goals; isDefault: boolean; onSave: (g: Goals) => Promise<boolean> }) {
  const [form, setForm] = useState(() => toForm(goals))
  const [saving, setSaving] = useState(false) // el padre cambia la key al guardar: el formulario se reinicia

  const parsed: Goals = { kcal: Number(form.kcal), proteinG: Number(form.proteinG), fatG: Number(form.fatG), carbsG: Number(form.carbsG) }
  const valid = Number.isInteger(parsed.kcal) && parsed.kcal >= 500 && parsed.kcal <= 10000
    && [parsed.proteinG, parsed.fatG].every((v) => Number.isFinite(v) && v >= 0 && v <= 1000) // mismos límites que el servidor
    && Number.isFinite(parsed.carbsG) && parsed.carbsG >= 0 && parsed.carbsG <= 1500
    && Object.values(form).every((v) => v.trim() !== '')
  const fromMacros = Math.round(parsed.proteinG * 4 + parsed.fatG * 9 + parsed.carbsG * 4)

  const fields: { key: keyof Goals; label: string; unit: string; step: string }[] = [
    { key: 'kcal', label: 'Calorías', unit: 'kcal', step: '1' },
    { key: 'proteinG', label: 'Proteínas', unit: 'g', step: '0.1' },
    { key: 'fatG', label: 'Grasas', unit: 'g', step: '0.1' },
    { key: 'carbsG', label: 'Carbohidratos', unit: 'g', step: '0.1' },
  ]

  return (
    <details className="card goals-card">
      <summary><h2>Objetivos diarios</h2><span className="subtitle">{isDefault ? 'Valores iniciales del plan' : 'Personalizados'}</span></summary>
      <form className="goals-form" onSubmit={async (e) => {
        e.preventDefault()
        if (!valid) return
        setSaving(true)
        await onSave(parsed)
        setSaving(false)
      }}>
        {fields.map((f) => (
          <label key={f.key} className="goal-field">
            <span>{f.label}</span>
            <span className="input-unit">
              <input type="number" inputMode="decimal" min={f.key === 'kcal' ? 500 : 0} step={f.step} value={form[f.key]}
                onChange={(e) => setForm((s) => ({ ...s, [f.key]: e.target.value }))} required />
              <span>{f.unit}</span>
            </span>
          </label>
        ))}
        <p className="footnote">
          Con los macros indicados salen ~{fmtInt(fromMacros)} kcal (4/9/4 kcal por gramo). Se guarda exactamente lo que escribas.
          Los cambios valen desde hoy: los días anteriores se siguen comparando con los objetivos que tenían.
        </p>
        <div className="goals-actions">
          <button className="btn" type="submit" disabled={!valid || saving}>{saving ? 'Guardando…' : 'Guardar objetivos'}</button>
          <button className="btn ghost" type="button" onClick={() => setForm(toForm(goals))}>Descartar cambios</button>
        </div>
      </form>
    </details>
  )
}

const EMPTY_CUSTOM = { name: '', servings: '1', kcal: '', proteinG: '', fatG: '', carbsG: '' }

/** Registro de algo que no está en el plan. Los valores los escribe el usuario (por ración); no se rellena nada. */
function CustomMealForm({ busy, disabled, onAdd }: { busy: boolean; disabled: boolean; onAdd: (f: CustomFood, servings: number) => Promise<void> }) {
  const [form, setForm] = useState(EMPTY_CUSTOM)
  const num = (v: string) => (v.trim() === '' ? NaN : Number(v.replace(',', '.')))
  const servings = num(form.servings)
  const food: CustomFood = { name: form.name.trim(), kcal: num(form.kcal), proteinG: num(form.proteinG), fatG: num(form.fatG), carbsG: num(form.carbsG) }
  const inRange = (v: number, max: number) => Number.isFinite(v) && v >= 0 && v <= max
  const valid = food.name.length >= 1 && food.name.length <= 100 && Number.isFinite(servings) && servings > 0 && servings <= 10
    && inRange(food.kcal, 5000) && inRange(food.proteinG, 500) && inRange(food.fatG, 500) && inRange(food.carbsG, 500)
  const set = (k: keyof typeof EMPTY_CUSTOM) => (e: { target: { value: string } }) => setForm((s) => ({ ...s, [k]: e.target.value }))

  const fields: { key: 'kcal' | 'proteinG' | 'fatG' | 'carbsG'; label: string; unit: string; max: number }[] = [
    { key: 'kcal', label: 'Calorías', unit: 'kcal', max: 5000 },
    { key: 'proteinG', label: 'Proteínas', unit: 'g', max: 500 },
    { key: 'fatG', label: 'Grasas', unit: 'g', max: 500 },
    { key: 'carbsG', label: 'Carbohidratos', unit: 'g', max: 500 },
  ]

  return (
    <details className="card goals-card">
      <summary><h2>Otra comida</h2><span className="subtitle">Algo fuera del plan, con sus valores</span></summary>
      <form className="goals-form" onSubmit={async (e) => {
        e.preventDefault()
        if (!valid || busy) return
        await onAdd(food, servings)
        setForm(EMPTY_CUSTOM)
      }}>
        <label className="goal-field wide">
          <span>Nombre</span>
          <span className="input-unit">
            <input type="text" value={form.name} onChange={set('name')} maxLength={100} required placeholder="p. ej. Bocadillo de tortilla" autoComplete="off" />
          </span>
        </label>
        <label className="goal-field">
          <span>Raciones</span>
          <span className="input-unit">
            <input type="number" inputMode="decimal" min={0.05} max={10} step="any" value={form.servings} onChange={set('servings')} required />
            <span>×</span>
          </span>
        </label>
        {fields.map((f) => (
          <label key={f.key} className="goal-field">
            <span>{f.label} <small className="muted">por ración</small></span>
            <span className="input-unit">
              <input type="number" inputMode="decimal" min={0} max={f.max} step="any" value={form[f.key]} onChange={set(f.key)} required />
              <span>{f.unit}</span>
            </span>
          </label>
        ))}
        <p className="footnote">Escribe los valores de la etiqueta o de tu fuente; el panel no los calcula ni los rellena.</p>
        <div className="goals-actions">
          <button className="btn" type="submit" disabled={!valid || busy || disabled}>{busy ? 'Guardando…' : 'Registrar comida'}</button>
          <button className="btn ghost" type="button" onClick={() => setForm(EMPTY_CUSTOM)}>Vaciar</button>
        </div>
      </form>
    </details>
  )
}

const toForm = (g: Goals) => ({ kcal: String(g.kcal), proteinG: String(g.proteinG), fatG: String(g.fatG), carbsG: String(g.carbsG) })
