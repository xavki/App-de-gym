# Esquemas de Room exportados (`exportSchema = true`)

| Versión | Archivo | Estado | Origen e identidad |
|---|---|---|---|
| 1 | `…/1.json` | en el repositorio | exportado por Room (historial previo a la sincronización) |
| 2 | `…/2.json` | en el repositorio | exportado por Room; sus tablas de datos son también las de la v3 y la v4 |
| 3 | `…/3.json` | **PENDIENTE — no incluido** | Debe proceder del build **histórico del commit `1a17b49`** (la v3 distribuida). `identityHash` esperado según la 2.ª y 3.ª auditoría de Codex: `32a6e958badc613142b6b8920ec7611a`. **No** se puede regenerar con el modelo actual (es v4) ni fabricar a mano. |
| 4 | `…/4.json` | **PENDIENTE — no incluido** | Lo genera KSP/Room al compilar el modelo actual (`GymDatabase` v4). `identityHash` esperado según la 3.ª auditoría: `d7996e1a59d6e18ca49dae537a03cc80`. |

Cómo incorporarlos (en un entorno con Android SDK): 
1. `git worktree add ../v3 1a17b49 && (cd ../v3 && gradle :app:kspDebugKotlin)` → copiar `app/schemas/com.gymflow.data.GymDatabase/3.json`.
2. En esta rama: `gradle :app:kspDebugKotlin` → `4.json`.
3. Comprobar los `identityHash` anteriores (`SchemaFilesGuardTest` los verifica cuando los archivos existen) y ejecutar `MigrationTest` en un AVD desechable desde un checkout limpio (el `build.gradle` ya empaqueta `schemas/` como assets de `androidTest`).
