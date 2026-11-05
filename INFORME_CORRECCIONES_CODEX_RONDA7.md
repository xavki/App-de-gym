# INFORME DE CORRECCIONES — Ronda 7 (tests instrumentados; tras la sexta auditoría de Codex sobre 5cb497e)

**Rama local:** `feature/import-sync-multidevice` · **HEAD auditado:** `5cb497e90887f71e05c0b0a3948176450088ba0c`
**Estado:** **TESTS CORREGIDOS — PENDIENTE DE SÉPTIMA AUDITORÍA CODEX.** Los cinco tests siguen **sin compilar ni ejecutar** aquí (sin Android SDK ni artefactos de Compose): no se declara ninguno aprobado.

Alcance respetado: **solo** `app/src/androidTest/java/com/gymflow/HomeProgressReloadTest.kt` (1 archivo, +65/−28). Sin cambios de producción, Gradle, dependencias, Room/esquemas, SessionGuard ni HistoryFingerprint; fixtures de Codex intactos. Sin push, merge, PR, release, instalación ni servicios de producción.

## 1. R6-01 — Visibilidad (tres tests)
- **Causa (según el informe):** un contenedor `requiredSize(412×3000 dp)` componía todos los nodos pero no garantizaba que estuvieran dentro de la ventana; `assertIsDisplayed` se exigía sin desplazarse. Error observado: `The component is not displayed!` en los estados iniciales de `homeShowsReplacedWorkoutWithSameCountAndSameId` y `progressRecordsReflectWeightChangeWithSameCount` y tras el renombrado en `homeReflectsNameChangeAndDateChangeWithSameCount`.
- **Corrección:** montaje con la **ventana normal** (`mount { … }`, sin contenedor forzado). `shown(texto)` se **desplaza** al nodo con la acción semántica del `LazyColumn` (`onNode(hasScrollToIndexAction()).performScrollToNode(hasText(texto))`; el scroll horizontal de los chips no expone `ScrollToIndex`, así que el selector es único) y exige **`assertIsDisplayed`** (se mantiene; no se sustituye por existencia). Coincidencia exacta del texto completo.
- **Se conserva:** precondición y estado posterior en cada test; orden cronológico por `boundsInRoot.top` (Alfa antes que Beta → fechas intercambiadas → Beta antes que Alfa → renombrado); valores exactos (800→1.000 kg; 480→800 kg; récord/1RM/fecha de Progreso).
- **Comprobaciones de ausencia (`gone`) y su límite:** con un `LazyColumn` solo existen los items compuestos, así que `gone` solo es una prueba válida si el valor antiguo vivía en el **mismo item** que un valor nuevo recién mostrado con `shown`. Los tests siguen ese orden (primero `shown(nuevo)`, luego `gone(antiguo)` del mismo item) y el helper lo documenta.

## 2. R6-02 — Descripción accesible del mapa (dos tests)
- **Causa:** `assertContentDescriptionContains("Pecho 3 series")` no hereda el `substring = true` del selector; usa su propio parámetro (por defecto, igualdad), mientras la descripción real es `"Mapa muscular, últimos 7 días: Pecho 3 series, Espalda 0 series, Hombros 0 series, …"`.
- **Corrección (`assertMap(chest, back)`):** desplazamiento al mapa + `assertIsDisplayed`; **igualdad de la descripción completa** (`assertContentDescriptionEquals`), construida con el mismo formato que producción a partir de `MuscleActivity.GROUPS` (los 11 grupos, con «1 serie»/«n series»), **y además** `assertContentDescriptionContains(…, substring = true)` explícito para Pecho y Espalda. Se verifica **Pecho 3 → 0 y Espalda 0 → 3** con cardinalidad constante (1 ejercicio personalizado / 1 global; mismo historial). Ambos tests (personalizado y global) se conservan.

## 3. Los cinco tests (todos conservados, mismo propósito)
`homeShowsReplacedWorkoutWithSameCountAndSameId` · `homeReflectsNameChangeAndDateChangeWithSameCount` · `progressRecordsReflectWeightChangeWithSameCount` · `muscleMapFollowsCustomExerciseGroupChangeWithSameCardinality` · `muscleMapFollowsGlobalCatalogGroupChangeWithSameCardinality`. Ninguna aserción se eliminó ni se debilitó; las de ausencia se mantienen y las de visibilidad ahora incluyen el scroll.

## 4. Verificación — qué se hizo y qué NO
- **Imports/API (por conocimiento; no resolví los artefactos):** `assertContentDescriptionContains(value, substring, ignoreCase)`, `assertContentDescriptionEquals`, `assertCountEquals`, `assertIsDisplayed`, `hasContentDescription(value, substring)`, `hasScrollToIndexAction`, `hasText`, `onAllNodesWithText`, `onNodeWithContentDescription`, `onNodeWithText`, `performScrollToNode` pertenecen a `androidx.compose.ui:ui-test` (ya declarada; BOM 2025.04.01), y `SemanticsNode.boundsInRoot` a `ui`. Sin import inválido (`assertDoesNotExist` ya no se importa y no se usa). Cada import usado figura en el archivo.
- **Sintaxis/estructura:** el archivo se pasó por el compilador de Kotlin: **0 errores de sintaxis**; los errores restantes son solo referencias sin resolver (Compose/Firebase/Room, ausentes en Linux) y su inferencia derivada. **Esto no es una compilación.**
- **Ejecutado y verde (sin relación directa con el cambio):** guardas JVM de `app` (Round3GuardTest 14, LegacyImportGuardTest 5, SchemaFilesGuardTest 3: 22/22, 0 omitidos; incluyen la guarda de que el test conserva sus tres tests originales y no importa `assertDoesNotExist`) y `syncCore` 135/135.
- **PENDIENTE (Codex Windows):** `:app:compileDebugAndroidTestKotlin`/`assembleDebugAndroidTest` y la ejecución de los **5 tests** en AVD desechable. **No se declara ninguno aprobado.**

## 5. Riesgos residuales
1. Sin compilar/ejecutar: pueden requerir ajustes finos (p. ej. que `hasScrollToIndexAction` sea único en cada pantalla, que `performScrollToNode` encuentre el item, o el formato exacto de la descripción).
2. `gone` depende del orden mostrar→ausente descrito en §1.
3. La igualdad de la descripción completa replica el formato de producción: si cambiara (p. ej. nuevos grupos), el test lo detectará y habrá que actualizarlo (intencionado).
4. Se mantienen los riesgos de rondas anteriores (ventana de commit Room/FirebaseAuth, M-03 sin recreación autenticada ni medición de rendimiento).

## 6. Commits (sobre 5cb497e) y HEAD
Un commit de código + este informe. Ver `COMMITS.txt`, `HEAD.txt`, `SHA256SUMS.txt`.

## 7. Para la séptima auditoría
`assembleDebugAndroidTest`, luego `HomeProgressReloadTest` completo (5 tests) en AVD desechable junto con el resto de la instrumentación; si algún `shown` falla, adjuntar el árbol semántico (`printToLog`) de ese punto.
