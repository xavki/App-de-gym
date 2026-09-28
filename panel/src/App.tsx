import { useEffect, useMemo, useRef, useState } from 'react'
import { clearLocal, loadLocal, parseExport, sampleExport, saveLocal } from './data'
import { RANGES, fmtDate, inRange, prepare, rangeBounds, type RangeKey } from './stats'
import type { GymExport } from './types'
import { Body } from './views/Body'
import { Exercises } from './views/Exercises'
import { Overview } from './views/Overview'
import { Workouts } from './views/Workouts'

type Tab = 'overview' | 'exercises' | 'body' | 'workouts'
const TABS: { key: Tab; label: string }[] = [
  { key: 'overview', label: 'Resumen' },
  { key: 'exercises', label: 'Ejercicios' },
  { key: 'body', label: 'Cuerpo' },
  { key: 'workouts', label: 'Entrenos' },
]

// URL: ?demo carga los datos de ejemplo; ?tab=exercises abre esa pestaña
const params = new URLSearchParams(window.location.search)

function initial(): { data: GymExport | null; demo: boolean } {
  if (params.has('demo')) return { data: sampleExport(), demo: true }
  const saved = loadLocal()
  if (!saved) return { data: null, demo: false }
  try { return { data: parseExport(saved), demo: false } } catch { return { data: null, demo: false } }
}

export default function App() {
  const [{ data, demo }, setState] = useState(initial)
  const [error, setError] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>(() => TABS.find((t) => t.key === params.get('tab'))?.key ?? 'overview')
  const [range, setRange] = useState<RangeKey>('90d')
  const [theme, setTheme] = useState<'light' | 'dark' | null>(() => {
    const t = params.get('theme')
    return t === 'light' || t === 'dark' ? t : null
  })
  const fileInput = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (theme) document.documentElement.dataset.theme = theme
    else delete document.documentElement.dataset.theme
  }, [theme])

  const openFile = async (file: File) => {
    try {
      const text = await file.text()
      const parsed = parseExport(text)
      saveLocal(text)
      setState({ data: parsed, demo: false })
      setError(null)
    } catch (e) {
      setError((e as Error).message)
    }
  }

  const workouts = useMemo(() => (data ? prepare(data) : []), [data])
  const bounds = useMemo(() => (data ? rangeBounds(range, workouts, data.exportedAt) : null), [data, workouts, range])
  const inR = useMemo(() => (bounds ? workouts.filter((w) => inRange(w.date, bounds)) : []), [workouts, bounds])

  const isDark = theme ? theme === 'dark' : window.matchMedia?.('(prefers-color-scheme: dark)').matches

  return (
    <div
      className="app"
      onDragOver={(e) => e.preventDefault()}
      onDrop={(e) => { e.preventDefault(); const f = e.dataTransfer.files[0]; if (f) openFile(f) }}
    >
      <header className="topbar">
        <div className="brand">
          <span className="logo" aria-hidden>◆</span>
          <span>GymFlow</span>
          <span className="muted">Panel</span>
        </div>
        <div className="topbar-actions">
          {data && (
            <span className="muted small file-info">
              {demo ? 'Datos de ejemplo' : `Exportado el ${fmtDate(new Date(data.exportedAt))}`} · {data.workouts.length} entrenos
            </span>
          )}
          {data && <button className="btn ghost" onClick={() => fileInput.current?.click()}>Cambiar archivo</button>}
          {data && !demo && (
            <button className="btn ghost" onClick={() => { clearLocal(); setState({ data: null, demo: false }) }}>Olvidar</button>
          )}
          <button className="btn ghost icon" onClick={() => setTheme(isDark ? 'light' : 'dark')} aria-label="Cambiar tema" title="Cambiar tema">
            {isDark ? '☀' : '☾'}
          </button>
        </div>
        <input
          ref={fileInput} type="file" accept="application/json,.json" hidden
          onChange={(e) => { const f = e.target.files?.[0]; if (f) openFile(f); e.target.value = '' }}
        />
      </header>

      {error && <div className="error" role="alert">{error}</div>}

      {!data ? (
        <main className="welcome">
          <h1>Tus entrenos, en grande</h1>
          <p className="muted">
            En la app ve a <strong>Perfil → Exportar datos → JSON</strong>, pasa el archivo al ordenador y ábrelo aquí.
            Todo se procesa en tu navegador: el archivo no se sube a ningún sitio.
          </p>
          <div className="dropzone" onClick={() => fileInput.current?.click()} role="button" tabIndex={0}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') fileInput.current?.click() }}>
            <strong>Arrastra aquí el JSON</strong>
            <span className="muted">o haz clic para elegirlo</span>
          </div>
          <button className="btn" onClick={() => { setState({ data: sampleExport(), demo: true }); setError(null) }}>
            Ver con datos de ejemplo
          </button>
        </main>
      ) : (
        <main className="content">
          <nav className="filters" aria-label="Filtros">
            <div className="tabs" role="tablist">
              {TABS.map((t) => (
                <button key={t.key} role="tab" aria-selected={tab === t.key} className={tab === t.key ? 'tab active' : 'tab'} onClick={() => setTab(t.key)}>
                  {t.label}
                </button>
              ))}
            </div>
            <div className="segmented" role="radiogroup" aria-label="Rango de fechas">
              {RANGES.map((r) => (
                <button key={r.key} role="radio" aria-checked={range === r.key} className={range === r.key ? 'seg active' : 'seg'} onClick={() => setRange(r.key)}>
                  {r.label}
                </button>
              ))}
            </div>
          </nav>

          {bounds && tab === 'overview' && <Overview workouts={inR} range={bounds} />}
          {bounds && tab === 'exercises' && <Exercises all={workouts} range={bounds} />}
          {bounds && tab === 'body' && <Body measurements={data.bodyMeasurements} range={bounds} />}
          {bounds && tab === 'workouts' && <Workouts workouts={inR} />}
        </main>
      )}
    </div>
  )
}
