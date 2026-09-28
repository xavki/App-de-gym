# GymFlow Panel

Panel web con las estadísticas de tus entrenos: volumen semanal, calendario, series por grupo
muscular, progresión de fuerza por ejercicio (1RM estimado, récords), peso corporal y el detalle
de cada entreno.

Lee el JSON que exporta la app (**Perfil → Exportar datos → JSON**). Todo se procesa en el
navegador; el archivo no se sube a ningún sitio. El último archivo abierto se recuerda en ese
navegador.

## Uso

```bash
npm install
npm run dev          # http://localhost:5173
```

- `http://localhost:5173/?demo` abre el panel con datos de ejemplo.
- `?tab=exercises|body|workouts` abre directamente esa pestaña; `?theme=dark|light` fuerza el tema.

Para generar la versión estática (carpeta `dist/`, se puede servir desde cualquier sitio):

```bash
npm run build
```

## Estructura

| Archivo | Qué hace |
|---|---|
| `src/types.ts` | Formato de la exportación (`schemaVersion` 1), igual que `DataExporter.kt` de la app |
| `src/data.ts` | Leer/validar el JSON, recordarlo en el navegador y los datos de ejemplo |
| `src/stats.ts` | Cálculos: volumen, series efectivas, 1RM (Brzycki ≤ 12 reps), récords, rachas |
| `src/charts/` | Gráficos en SVG propio (líneas, columnas, barras, calendario) con tooltip y teclado |
| `src/views/` | Pestañas: Resumen, Ejercicios, Cuerpo, Entrenos |

Reglas de cálculo (las mismas que la app): las series de calentamiento no cuentan para volumen,
series ni récords; el 1RM estimado solo usa series de 1 a 12 repeticiones.

Cuando exista el backend, basta con añadir en `src/data.ts` una función que devuelva el mismo
`GymExport` desde la API: el resto del panel no cambia.
