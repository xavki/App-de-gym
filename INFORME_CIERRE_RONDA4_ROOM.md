# INFORME DE CIERRE DE RONDA 4 — R3-04 (esquemas Room 3 y 4)

**Estado: R3-04 IMPLEMENTADO — PENDIENTE DE VERIFICACIÓN CODEX WINDOWS.** GymFlow no se declara aprobado ni se publica la rama.

Rama `feature/import-sync-multidevice`; HEAD de partida `74199a3277af2777ae64538102214c66d7eb6156`, árbol limpio. HEAD nuevo: ver `HEAD.txt` / `SHA256SUMS.txt`. Un único commit local. Sin push, merge, PR, release, despliegue ni instalación en teléfonos.

## 1. Cómo se incorporaron los esquemas (dato importante)
Los dos JSON llegaron **pegados como texto en el mensaje**, no como archivos adjuntos (el adjunto `SHA256SUMS.txt` recibido es el de la entrega de la ronda 4, no el de los esquemas). Por tanto **no son una copia de archivo**: los he reconstruido a partir de la estructura pegada y verificado que el resultado es **byte-idéntico** al original comparando con los SHA-256 y tamaños que declara `PROCEDENCIA_SCHEMAS.md`:

| Archivo | SHA-256 obtenido | Esperado | Bytes | Esperado |
|---|---|---|---|---|
| `3.json` | `6c67f70849278a6de558fe0a674eb50bdb8416b6a8ad97afd27d10bb979b6296` | idéntico | 38483 | 38483 |
| `4.json` | `a7ed3adc43689fae3fea97756f37c46aacb2392e581e0f29b8bdf3cffebc864a` | idéntico | 38695 | 38695 |

Un SHA-256 coincidente en ambos archivos prueba la identidad del contenido (LF, sin salto final). **Codex debe confirmar** estos hashes contra `gymflow-room-schemas-ronda4\SHA256SUMS.txt` y, si lo desea, `fc`/`cmp` contra sus originales. Los JSON no se regeneraron con Gradle/KSP ni se tocó el modelo.

## 2. Comprobaciones pedidas
1. Rama/HEAD/árbol limpio: ✔.
2. `PROCEDENCIA_SCHEMAS.md`: leído íntegro (texto del mensaje).
3. SHA-256 de ambos JSON vs los de la tabla de PROCEDENCIA: ✔ (arriba).
4. `identityHash` internos: 3 → `32a6e958badc613142b6b8920ec7611a` ✔; 4 → `d7996e1a59d6e18ca49dae537a03cc80` ✔ (también en su `setupQueries`).
5. `com.gymflow.data.GymDatabase`: ruta de exportación `app/schemas/com.gymflow.data.GymDatabase/` (el JSON no contiene el nombre de la clase; igual que indica la procedencia).
6. Copiados sin alterar a `app/schemas/com.gymflow.data.GymDatabase/{3,4}.json`; 1.json y 2.json intactos.
7. Guardas ejecutadas (§3).
8. Empaquetado para `MigrationTestHelper`: `app/build.gradle` → `androidTest.assets.srcDirs += files("$projectDir/schemas")` ya existente; sin cambios.
9–10. 3.json no regenerado; migraciones, modelos, sincronización y producción sin tocar.

## 3. Pruebas ejecutadas (Linux)
| Verificación | Resultado |
|---|---|
| `SchemaFilesGuardTest` (harness JVM, fuentes reales) | **3/3 pasan, 0 omitidos** (antes: 2 omitidos) |
| `Round3GuardTest` + `LegacyImportGuardTest` (mismo harness) | 9/9 y 5/5 pasan |
| `syncCore` completo | **109 tests, 0 fallos** (106 + 3 nuevos) |
| `RoomSchemaFilesTest` (nuevo, syncCore) | 3/3: SHA-256 e identidad de ambos esquemas; 14 entidades; **única diferencia de modelo 3→4 = clave de `sync_revs`**, con el DDL de `MIGRATION_2_3` == esquema 3 y el DDL v4 == esquema 4; **una base creada con el DDL del esquema 3 + `MIGRATE_3_4` produce exactamente el DDL (tablas, columnas, claves, índices) del esquema 4** y conserva la revisión asignada a su usuario (SQLite real, sqlite-jdbc) |
| Servidor | no re-ejecutado: sin cambios desde la ronda 4 (51/51 entonces) |

## 4. No ejecutado / límites
- `:app:compileDebugKotlin`, JVM de `app` completa, `assembleDebug`, `assembleDebugAndroidTest`: no ejecutados (sin SDK/Google Maven en Linux).
- `MigrationTest`, `RoomStoresTest`, `GoogleSignInRequestTest`, `TaskAwaitTest`: no ejecutados; **pendientes en Codex Windows/AVD desechable**. Mi verificación 3→4 es SQLite de escritorio, no `MigrationTestHelper`/Room.
- Pendientes heredados de la ronda 4 (sin cambios): compilar el código nuevo de `FirestoreImporter`/`GymRepository`/`GymFlowViewModel`, prueba visual de M-03, `TaskAwaitTest` con Tasks reales.
- M-01: la evidencia de que los esquemas y migraciones son correctos en Room real la aportó Codex (4/4 `MigrationTest` en API 35 con estos mismos esquemas); desde un checkout limpio queda por repetir ahora que están versionados.

## 5. Archivos del commit
`app/schemas/com.gymflow.data.GymDatabase/3.json`, `4.json` (nuevos); `app/schemas/README.md` (reescrito: procedencia, hashes, bytes); `syncCore/src/test/.../RoomSchemaFilesTest.kt` (nuevo); este informe.

## 6. Verificación para Codex Windows
1. `git bundle verify`; comparar SHA-256 de bundle e informe.
2. `Get-FileHash app\schemas\com.gymflow.data.GymDatabase\3.json,4.json` == tabla del §1 (con checkout CRLF, `core.autocrlf` puede alterar el hash: comparar con `git show HEAD:<ruta> | sha256sum` o normalizar).
3. Checkout limpio **sin** assets manuales: `:app:assembleDebugAndroidTest` y `MigrationTest` (4/4 esperado) + `RoomStoresTest`, `GoogleSignInRequestTest`, `TaskAwaitTest`; `:app:testDebugUnitTest` (incluye `SchemaFilesGuardTest`).
