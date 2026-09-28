import { LineChart } from '../charts/LineChart'
import { fmtDate, fmtKg, fmtNum, inRange } from '../stats'
import type { ExportBodyMeasurement } from '../types'
import { ChartCard, DataTable, StatTile } from '../ui'

const cm = (v: number) => (v > 0 ? `${fmtNum(v)} cm` : '—')

export function Body({ measurements, range }: { measurements: ExportBodyMeasurement[]; range: [Date, Date] }) {
  const all = measurements
    .map((m) => ({ ...m, date: new Date(m.measuredAt) }))
    .sort((a, b) => a.date.getTime() - b.date.getTime())
  const list = all.filter((m) => inRange(m.date, range))
  const weights = list.filter((m) => m.weightKg > 0)
  const fat = list.filter((m) => m.bodyFatPct > 0)

  if (!all.length) return <div className="empty big">Aún no hay medidas corporales. Regístralas en la app (Perfil → Medidas).</div>

  const first = weights[0], last = weights.at(-1)
  const delta = first && last ? last.weightKg - first.weightKg : 0

  return (
    <>
      <div className="stats">
        <StatTile label="Peso actual" value={last ? fmtKg(last.weightKg) : '—'} hint={last && fmtDate(last.date)} />
        <StatTile label="Cambio en el rango" value={first && last ? `${delta > 0 ? '+' : delta < 0 ? '−' : ''}${fmtKg(Math.abs(delta))}` : '—'} />
        <StatTile label="Grasa corporal" value={fat.length ? `${fmtNum(fat.at(-1)!.bodyFatPct)} %` : '—'} />
        <StatTile label="Registros" value={`${list.length}`} hint="En el rango" />
      </div>
      <div className="grid">
        <ChartCard
          wide title="Peso corporal"
          subtitle="Se guarda con cada entreno: servirá para comparar fuerza relativa (niveles)"
          table={{ columns: ['Fecha', 'Peso'], rows: weights.map((m) => [fmtDate(m.date), fmtKg(m.weightKg)]), numeric: [false, true] }}
        >
          <LineChart series={[{ id: 'bw', label: 'Peso corporal', color: 'var(--series-1)', points: weights.map((m) => ({ x: m.date, y: m.weightKg })) }]} format={(v) => `${v.toFixed(1).replace('.', ',')} kg`} area />
        </ChartCard>
        {fat.length > 1 && (
          <ChartCard wide title="Grasa corporal" table={{ columns: ['Fecha', '%'], rows: fat.map((m) => [fmtDate(m.date), fmtNum(m.bodyFatPct)]), numeric: [false, true] }}>
            <LineChart series={[{ id: 'bf', label: 'Grasa corporal', color: 'var(--series-1)', points: fat.map((m) => ({ x: m.date, y: m.bodyFatPct })) }]} format={(v) => `${v.toFixed(1).replace('.', ',')} %`} />
          </ChartCard>
        )}
        <ChartCard wide title="Medidas">
          <DataTable
            columns={['Fecha', 'Peso', 'Grasa', 'Pecho', 'Cintura', 'Cadera', 'Bíceps', 'Muslo']}
            numeric={[false, true, true, true, true, true, true, true]}
            rows={[...list].reverse().map((m) => [
              fmtDate(m.date), m.weightKg ? fmtKg(m.weightKg) : '—', m.bodyFatPct ? `${fmtNum(m.bodyFatPct)} %` : '—',
              cm(m.chestCm), cm(m.waistCm), cm(m.hipsCm), cm(m.bicepCm), cm(m.thighCm),
            ])}
          />
        </ChartCard>
      </div>
    </>
  )
}
