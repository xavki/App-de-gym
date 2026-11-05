# INFORME DE CORRECCIONES — Ronda 6 (tras la quinta auditoría de Codex sobre c4d9d70)

**Rama local:** `feature/import-sync-multidevice` · **HEAD auditado:** `c4d9d7062ae6f489b3b5d7c64e33fc636a463ce7`
**Estado:** **CORRECCIONES IMPLEMENTADAS — PENDIENTE DE SEXTA AUDITORÍA CODEX.** Android sigue sin poder compilarse ni ejecutarse en Linux: nada instrumentado ni Compose está verificado (§5). No se declara aprobado nada.

Restricciones respetadas: sin push, merge, PR, release, despliegue, instalación en móviles, Firebase de producción ni datos personales. Commits previos intactos; esquemas `3.json`/`4.json` sin tocar (SHA-256 `6c67f708…`/`a7ed3adc…` verificados en el clon CRLF). Datos sintéticos.

## 1. R5-01 — Test Compose no compilaba (corregido; NO compilado ni ejecutado)
- **Causa:** `androidx.compose.ui.test.assertDoesNotExist` no existe como extensión: `assertDoesNotExist()` es un **miembro** de `SemanticsNodeInteraction` (no lleva import). Para «no existe entre varios nodos» se usa `onAllNodesWithText(..).assertCountEquals(0)`.
- **Solución:** import eliminado; **conserva los tres tests originales** (`homeShowsReplacedWorkoutWithSameCountAndSameId`, `homeReflectsNameChangeAndDateChangeWithSameCount`, `progressRecordsReflectWeightChangeWithSameCount`) y añade dos de mapa muscular. Sin cambios de dependencias (Compose BOM 2025.04.01).
- **Comprobaciones más estrictas:** precondición y estado posterior en cada caso; coincidencia **exacta** del texto completo (ya no hay `substring = true` en `onNodeWithText`, lo que impide coincidencias ambiguas — un nodo duplicado haría fallar la aserción en vez de pasar); volumen, filas y récords con los **valores exactos** (800 kg → 1.000 kg; 480 → 800; 1RM Brzycki y «N sesiones · máx. X × reps · fecha» usando los mismos formateadores de producción, más aserciones numéricas fijas con `TrainingStats.volume`); **orden cronológico real** comparando `boundsInRoot.top` de las filas, con intercambio de fechas y renombrado; fecha del récord de Progreso. Contenedor de 3000 dp para que el `LazyColumn` componga todas las filas.
- **Inspección de APIs:** los imports usados (`assertContentDescriptionContains`, `assertCountEquals`, `assertIsDisplayed`, `onAllNodesWithText`, `onNodeWithContentDescription`, `onNodeWithText`, `createComposeRule`) pertenecen a `androidx.compose.ui:ui-test` / `ui-test-junit4`, ya declaradas. **No pude resolver los artefactos** (dl.google.com bloqueado) **ni compilar**: la verificación es por conocimiento de la API y una guarda estática (el import inválido no puede reaparecer). La compilación real queda para Codex.

## 2. R5-02 — Rollback con cambio de cuenta durante `write` y logros (corregido en el núcleo; Room real sin ejecutar)
- **Causa:** tras `write` no se revalidaba la identidad dentro de la unidad transaccional; la comprobación previa a `onComplete` detectaba el cambio con el agregado ya confirmado.
- **Solución (`runLegacyImport`):** nueva comprobación **dentro de `atomically`, justo después de `write`**: si cambió, lanza `IdentityChangedException` y `atomically` (Room: `withTransaction`) **revierte ese agregado**; los anteriores, ya confirmados, se conservan. Los logros son agregados de la misma ruta, así que quedan cubiertos. La marca final solo existe en `onComplete`, precedida por la comprobación del helper y otra pegada a `putBoolean` en el importador. `CancellationException` se propaga siempre.
- **A→B→A y generación de sesión:** comparar el UID no lo detecta (se añadió una prueba que **documenta** esa limitación). Nuevo `SessionGuard` (`SessionToken(uid, generación)`): el `AuthStateListener` de `GymFlowViewModel` sube la generación en cada cambio de autenticación; `importLegacyFirestore` captura el token al iniciar y `stillActive = sessions.isCurrent(token, uid activo)`. El listener ahora tiene referencia propia y **se retira en `onCleared`**.
- **Límite honesto:** esto estrecha la ventana, no la elimina. Entre la última comprobación dentro de la transacción y el commit de Room, FirebaseAuth puede cambiar en otro hilo (Room no coordina estado externo). Lo que podría quedar en esa ventana es el agregado de la cuenta capturada, persistido siempre con su propio `userId` (nunca bajo la cuenta nueva) e idempotente al reintentar. Cerrarla del todo exigiría un bloqueo compartido entre el listener de sesión y el commit; no se implementó. La validación sobre Room real queda para Codex Windows.
- **Pruebas (todas pasan, `LegacyImportInterleavingTest` +7, `LegacyImportIdentityTest` 9, `LegacyImportTest` 4):** cambio durante `ownerOf`; al entrar en la transacción; **mientras `write` está suspendido** (intercalación con `CompletableDeferred`: el agregado en curso se revierte y el anterior se conserva); **durante la escritura de un logro** (sin `onComplete`); justo antes del commit (tras terminar `write`); antes de marcar completo; cancelación durante `write` (con rollback); reintento tras fallo parcial (sin duplicar); **A→B→A** detectado por generación aunque el UID coincida; y `SessionGuard` por sí solo. Rollback verificable con una `atomically` de prueba que restaura una instantánea. Dos pruebas previas que dependían de **cuántas** comprobaciones hace el helper se reformularon (misma propiedad: nada queda persistido tras el cambio; ahora el agregado interrumpido se revierte).

## 3. R5-03 — Mapa muscular y catálogo (corregido; Compose sin ejecutar)
- **Causa:** `remember(contentKey, catalog.size)` y `ExerciseRepository.getCached().size` no cambian si un ejercicio pasa de Pecho a Espalda con el mismo nº de ejercicios.
- **Solución:** `MuscleMapCard` cachea por `remember(contentKey, catalog)` (igualdad por contenido del `Map`; camino rápido por referencia cuando es la misma instancia, comparación profunda solo cuando se reconstruye). El productor se invalida con `customExercises.toList()` (snapshot por contenido) y con `ExerciseRepository.catalogRevision`, un **estado observable** nuevo (subido por `replaceCached`, que usa la carga del catálogo) que además hace recomponer la pantalla. `getCached().size` ya no se usa como clave; se evita recalcular en cada composición.
- **Pruebas Compose reales (escritas, NO ejecutadas):** `muscleMapFollowsCustomExerciseGroupChangeWithSameCardinality` y `muscleMapFollowsGlobalCatalogGroupChangeWithSameCardinality`: ejercicio X en Pecho con 3 series → X a Espalda, mismo historial, mismo número de ejercicios; se comprueba la descripción de accesibilidad del mapa («Pecho 3 series… Espalda 0 series» → «Pecho 0 series… Espalda 3 series»). No se considera cerrado solo por un test del cálculo.

## 4. M-03 (revisión)
Inicio con entrenos sustituidos, Progreso con peso/fecha, orden cronológico, récords y mapa: cubiertos por los tests Compose anteriores (sin ejecutar). Cambio de cuenta y recargas solapadas: `ListPublisherTest`/`ReloadGate` en JVM (9 pruebas, verdes); no hay sesión Firebase local para Compose. Listener de `FirebaseAuth` de `GymFlowViewModel` ahora se retira en `onCleared` (guarda estática; sin prueba instrumentada de ciclo de vida). Otras observaciones de Codex (flags de carga sin `finally` general, `DataEvents` sin replay, coste de la huella en historiales grandes sin medir en Android) siguen sin cambios; **M-03 no se considera cerrado**.

## 5. Resultados reales (Linux)
| Verificación | Resultado |
|---|---|
| `syncCore` completo, árbol de trabajo | **135 tests, 0 fallos** (128 + 7 nuevos) |
| `syncCore` completo en clon `core.autocrlf=true` del HEAD (esquemas `w/lf`) | **135/135** |
| Servidor completo (PostgreSQL embebido) | **51/51** |
| Guardas JVM de `app` en harness aislado sobre el clon CRLF (`Round3GuardTest` 14, `LegacyImportGuardTest` 5, `SchemaFilesGuardTest` 3) | **22/22, 0 omitidos** |
| `HistoryFingerprintTest` (modelos reales) | 10/10 |
| Pruebas nuevas de identidad (`LegacyImportInterleavingTest` 7 + `LegacyImportIdentityTest` 9) | 16/16 |
| Pruebas de caché/recarga (`ListPublisherTest`) | 9/9 |

**NO ejecutado / NO verificado:** `:app:compileDebugKotlin`, `:app:testDebugUnitTest` completa, `assembleDebug`, **`assembleDebugAndroidTest`** (precisamente lo que falló en la ronda 5), `MigrationTest`, `RoomStoresTest`, `GoogleSignInRequestTest`, `TaskAwaitTest`, los **5 tests de `HomeProgressReloadTest`**, y la reproducción de Codex `Round4IndependentHomeReloadTest`. El código modificado en `ExerciseRepository`, `GymFlowViewModel` (SessionGuard/listener), `FirestoreImporter`, `ProgressScreens`, `MuscleMapScreen` y el test Compose **no ha sido compilado por nadie** desde estos cambios.

## 6. Archivos modificados
`syncCore`: `LegacyImport.kt`, `SessionGuard.kt` (nuevo). `app`: `GymFlowViewModel.kt`, `FirestoreImporter.kt`, `ExerciseRepository.kt`, `ProgressScreens.kt` (`fmtDay` pasa a público para el test), `MuscleMapScreen.kt`. Tests: `LegacyImportTest.kt`, `Round3GuardTest.kt`, `HomeProgressReloadTest.kt` (androidTest).

## 7. Riesgos residuales
1. Android sin compilar/ejecutar; el test Compose puede requerir ajustes (p. ej. semántica de `boundsInRoot`, formato de `fmtDay`, tamaño del contenedor).
2. Ventana mínima entre la última revalidación y el commit de Room (§2). 3. La relectura del propietario y las comprobaciones dentro de `withTransaction` dependen de que Room anide las transacciones de `save*` (no probado en dispositivo). 4. Coste de la huella de historial O(series) por composición, sin medir en Android. 5. Importación heredada incremental (no atómica global); con colisiones omitidas se marca completa. 6. `DataEvents` sin replay; flags de carga sin `finally` general.

## 8. Commits nuevos (sobre c4d9d70) y HEAD
`27c40a5` (R5-01/02/03) · commit de este informe. HEAD completo: ver `HEAD.txt` / `SHA256SUMS.txt`.

## 9. Instrucciones para la sexta auditoría
1. Verificar bundle y SHA-256. 2. `:app:assembleDebugAndroidTest` debe compilar (primer punto de fallo anterior). 3. En AVD desechable: los 5 tests de `HomeProgressReloadTest`, tu fixture `Round4IndependentHomeReloadTest`, `MigrationTest`, `RoomStoresTest`, `GoogleSignInRequestTest`, `TaskAwaitTest`. 4. Repetir tus fixtures `switchWhileWriteSuspendedMustRollback` y `switchDuringAchievementWriteMustRollback` (ahora deben revertir) y, si quieres, un A→B→A con generación de sesión sobre Room real. 5. Medir la huella en Android si el historial puede ser grande.
