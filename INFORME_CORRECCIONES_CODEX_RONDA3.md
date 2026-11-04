# INFORME DE CORRECCIONES — Ronda 3 (tras la segunda auditoría de Codex sobre b62f065)

**Rama local:** `feature/import-sync-multidevice` · **HEAD auditado:** `b62f065` · **Base original:** `895f1fb`
**Estado:** **CORRECCIONES IMPLEMENTADAS — PENDIENTE DE TERCERA AUDITORÍA CODEX.** No se declara aprobado nada: Android sigue sin poder compilarse en este entorno (ver §6).

Restricciones respetadas: sin push, merge, PR, releases, despliegues, instalaciones en Samsung/POCO, acceso a Firebase de producción ni datos reales. Commits anteriores intactos (solo se añaden commits). Datos de prueba 100 % sintéticos.

## 1. Causa raíz por hallazgo

| ID | Causa raíz |
|---|---|
| N-01 | La extensión privada `Task.await()` se borró de `FirestoreImporter.kt` al reescribirlo; el proyecto no tiene `kotlinx-coroutines-play-services` y la única copia era `private` en `SyncManager.kt`. |
| N-02 | El almacén de importación consultaba `SyncManager.liveIdentity`, que exige URL de servidor; sin servidor devolvía `null` y `requireSame` rechazaba. Se mezclaba la identidad de una operación local con la de una remota. |
| N-03 | `planImport` capturaba el UID, suspendía en IO y luego construía el almacén con la sesión **de ese momento**; el plan unía UID de A con contexto de B sin comprobarlo. Los jobs pendientes no se cancelaban. |
| N-04 | `keepLocal`/`useRemote` comprobaban la identidad solo al empezar, no justo antes de confirmar. |
| N-05 | Se editó el DDL de `sync_revs` de la v3 ya distribuida sin subir versión: otro significado para la misma versión. |
| N-06 | `OwnerGuard` acepta padre ausente/huérfano fila a fila y `applyAll` no validaba el grafo completo antes de persistir y avanzar el cursor. |
| N-07 | La guarda estática recortaba el `init` con `substringBefore("private fun loadAll")`, que incluía la función de acción explícita. |
| N-08 | El listener de sesión se registraba como lambda anónima y nunca se retiraba. |
| N-09 | El importador legacy escribe con varias transacciones independientes; el mensaje de la UI afirmaba «no se guardó nada a medias». |
| M-03 | Importar, incorporar y resolver cambian Room sin pasar por una descarga; la pantalla principal solo recargaba con `SyncState.Done(pulled>0)`. |

## 2. Correcciones aplicadas

- **C1 (N-01):** nuevo `data/TaskAwait.kt` (`internal suspend fun <T> Task<T>.await()`, sin dependencias nuevas, resultado tardío ignorado si se cancela). `SyncManager` lo importa en lugar de su copia privada.
- **C2 (N-02):** identidad de importación **local** = `uid|local` (`SyncManager.liveLocalIdentity()`); `RoomImportStore` ya no depende del servidor. Sincronización e incorporación siguen usando `uid|serverKey`. El rechazo por cambio de cuenta se mantiene.
- **C3 (N-03):** `planImport` captura el UID **una vez** antes de suspender, construye el almacén con él y re-comprueba la sesión tras la lectura del archivo. `ImportPlanner.plan` lanza `IdentityChangedException` si el UID del archivo y el de la operación no coinciden. `applyImport` liga el almacén al UID **del plan** (no al de la sesión actual). `ImportSyncViewModel`: generación de cuenta + cancelación de jobs; los resultados tardíos de la cuenta anterior no se publican; el listener se retira en `onCleared` (N-08).
- **C4 (N-04):** `useRemote` y `keepLocal` hacen `requireSame` también justo antes de confirmar. La UI guarda la identidad con la que se cargó la lista de conflictos y `SyncService.resolve` exige que coincida con la del almacén (si no, `IdentityChanged`). Siguen archivando antes de descartar (H3).
- **C5 (N-06):** `SyncRunner.assertGraphComplete` valida, dentro de la misma transacción que aplica la descarga y el cursor, que todo registro recibido tiene su cadena de padres hasta la raíz (ignorando lo congelado en un conflicto). Si falta algo: `Failed(SERVER, retryable)`, rollback, cursor sin avanzar, reintento posterior converge.
- **C6 (N-05):** `GymDatabase` **versión 4**. `MIGRATION_2_3` vuelve a crear `sync_revs` con la clave **v3 original** (`RoomMigrationSql.SYNC_REVS_V3`). Nueva `MIGRATION_3_4` (sentencias en `RoomMigrationSql`, Kotlin puro): crea `sync_revs_new`, copia cada revisión asignándola al usuario propietario (por la cadena de padres), descarta las huérfanas, borra la antigua y renombra. No toca tablas de datos; sin migración destructiva.
- **C8 (M-03):** `DataEvents` (flujo compartido con fusión de avisos, sin bucles). `SyncService` lo emite tras importar, incorporar y resolver; `GymFlowViewModel` recarga listas, alarmas y pendientes. (Room reactivo con `Flow` no encaja con la arquitectura actual, basada en `loadAll()`.)
- **N-07:** la guarda delimita el bloque por balanceo de llaves y se añadió una prueba que demuestra que detecta una llamada automática.
- **N-09:** el mensaje ahora es veraz («lo ya añadido se conserva, sin duplicados; puedes reintentar»). La atomicidad global del importador legacy **no** se ha implementado (ver §9).
- **Oversized:** `SyncState.Done.oversized` y texto en `SyncScreen`.

## 3. Archivos modificados

Núcleo: `ImportPlanner.kt`, `Onboarding.kt`, `SyncRunner.kt`, `RoomMigrationSql.kt` (nuevo). Android: `Migrations.kt`, `GymDatabase.kt`, `TaskAwait.kt` (nuevo), `DataEvents.kt` (nuevo), `SyncManager.kt`, `SyncService.kt`, `ImportSyncViewModel.kt`, `GymFlowViewModel.kt`, `SyncScreen.kt`, `FirestoreImporter.kt`. Pruebas: `CodexRound3Test.kt`, `RoomMigrationSqlTest.kt`, `Fixtures.kt`, `syncCore/build.gradle.kts` (sqlite-jdbc solo test), `Round3GuardTest.kt`, `LegacyImportGuardTest.kt`, `MigrationTest.kt` (androidTest).

## 4. Pruebas nuevas

- `CodexRound3Test` (8): N-02 ×2, N-03, N-04 ×2 (useRemote y keepLocal con cambio de cuenta durante la resolución → rollback), N-06 (hijo sin padre no se confirma ni avanza el cursor; se recupera al llegar el padre), C5 hijo antes que padre con páginas de 1 fila, C5 corte de red entre páginas con padre pendiente.
- `RoomMigrationSqlTest` (3): ejecuta el SQL real de 3→4 en **SQLite real** (sqlite-jdbc) sobre el esquema de tablas exportado por Room (`2.json`): conserva filas de 7 tablas, asigna revisiones a su usuario, aislamiento A/B, descarta huérfanas, PK y forma exactas de la v4, base vacía.
- `Round3GuardTest` (5) y `LegacyImportGuardTest` (5, corregido): guardas estáticas de N-01, N-02, N-05, M-03, N-08, N-07.
- `MigrationTest` (androidTest) +2: 3→4 y 1→4 con `MigrationTestHelper`. **No ejecutadas.**

Los cuatro fallos nuevos de Codex (N-02, N-03, N-04, N-06) están cubiertos por pruebas equivalentes a sus reproducciones (`new01`–`new04`); no dispongo de su fixture externo, así que **no** afirmo haber ejecutado sus mismos archivos.

## 5. Resultados exactos (ejecutados aquí)

| Verificación | Resultado |
|---|---|
| `syncCore` (`gradle test`) | **91 tests, 0 fallos** (80 previos + 8 + 3) |
| Servidor | **51 tests, 0 fallos** — ejecutado en la ronda anterior; el código del servidor **no cambió** en esta ronda |
| `LegacyImportGuardTest` + `Round3GuardTest` en harness JVM aislado (JUnit 4, workingDir=`app`, fuentes reales) | **10 tests, 0 fallos** |
| Type-check con stubs (`ktcheck`) de `sync/*`, `SyncEntities`, `TaskAwait`, `ImportSyncViewModel` y núcleo | OK |

## 6. Estado de compilación Android — NO verificado

No hay SDK de Android ni Google Maven en este entorno. **No he compilado `:app`.** `FirestoreImporter.kt`, `GymFlowViewModel.kt`, `ImportScreen.kt`, `HomeScreen.kt`, `SyncScreen.kt`, `Migrations.kt`, `GymDatabase.kt` y los tests de `app` no están cubiertos ni por el type-check con stubs. La corrección de N-01 se apoya en que `await` está ahora en el mismo paquete (`com.gymflow.data`) que `FirestoreImporter`, y en una guarda estática; **no** en una compilación real. 120 pruebas JVM de `app`, `assembleDebug`, `assembleDebugAndroidTest` y pruebas instrumentadas: **no ejecutadas**.

## 7. Estado de migraciones Room

- Verificado (SQLite de escritorio): el SQL de 3→4 y su resultado (§4).
- **No verificado:** apertura/validación por Room (`MigrationTestHelper`), hash de identidad, índices y FKs reales, ni `3.json`/`4.json`. **`4.json` no existe en el repositorio** (lo genera KSP al compilar) y **`3.json` (v3 distribuida, commit `1a17b49`) tampoco**: hay que generarlo compilando ese commit y versionarlo junto a `4.json`; sin ellos `MigrationTest` 2→3 y 3→4 no pueden ejecutarse. 
- Limitación asumida: revisiones de `sync_revs` sin fila de datos o sin propietario se descartan (es solo caché; la siguiente sincronización reconoce el contenido idéntico).

## 8. M-01 y M-03

- **M-01: ABIERTO / pendiente de verificación independiente.** Hay evidencia SQL de escritorio, no de Room ni de esquemas exportados.
- **M-03: implementado, no verificado en pantalla.** Cobertura: guarda estática; la recarga real de listas requiere ejecutar la app.

## 9. Riesgos pendientes

1. Android sin compilar (principal): pueden quedar errores en archivos fuera del type-check.
2. Importación legacy de Firestore **no atómica** (varias transacciones); es incremental y sin duplicados por ids estables, y el mensaje ya lo dice. Si se exige atomicidad total, queda como trabajo.
3. `OwnerGuard` rechaza colisiones de id entre cuentas (PK de datos global); no se soporta almacenar ambas filas con el mismo id.
4. `assertGraphComplete` hace que un servidor que envía datos incompletos deje la sincronización fallando (reintentable) hasta que se complete.
5. A→B→A entre comprobaciones sigue sin detectarse mediante contador de sesión (la generación de la VM mitiga solo la capa de pantalla).
6. La descarga completa en memoria sigue limitada por nº de cambios, no por bytes.
7. Estados persistidos entre reinicios (operaciones «preparadas») no existen: la vista previa vive en memoria y se pierde al reiniciar, por lo que no hay reanudación que atribuir a otra cuenta; el aislamiento tras reinicio se apoya en `sync_state/sync_conflicts/sync_archive` con clave `userId`, sin prueba instrumentada.

## 10. Commits nuevos (sobre b62f065)

```
bcd4e4a fix(syncCore): N-03 N-04 N-06 ...
6d98815 fix(room): N-05 versión 4 con migración 3->4 ...
8f253eb fix(android): N-01 N-02 N-03/C4 N-07 N-08 M-03 N-09 ...
```
(+ commit de este informe; HEAD final en §11 de la entrega.)

## 11. HEAD final
Se indica en el mensaje de entrega y en `SHA256SUMS.txt` (el bundle incluye el commit).

## 12. Instrucciones para la tercera auditoría

1. `git bundle verify` del incremental (requiere `b62f065`); comprobar SHA-256.
2. Compilar **primero** `:app:compileDebugKotlin`; después `:app:testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest`.
3. Generar el esquema v3 distribuido compilando `1a17b49` (copiar `3.json` a `app/schemas/com.gymflow.data.GymDatabase/`) y `4.json` con esta rama; ejecutar `MigrationTest` (2→3, 3→4, 1→4) y `RoomStoresTest` en AVD desechable.
4. Repetir `new01`–`new04` sobre este HEAD; añadir un caso de importación sin URL de servidor en Room real.
5. Revisar manualmente: importar JSON sin servidor; cambio de cuenta entre vista previa y confirmación; diálogos de conflicto; recarga de listas tras importar/resolver sin reiniciar.
