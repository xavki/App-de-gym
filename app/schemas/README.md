# Esquemas de Room exportados (`exportSchema = true`) — `com.gymflow.data.GymDatabase`

| Versión | Archivo | Origen | identityHash | SHA-256 | Bytes |
|---|---|---|---|---|---|
| 1 | `…/1.json` | exportado por Room (historial previo a la sincronización) | — | — | — |
| 2 | `…/2.json` | exportado por Room; sus tablas de datos son también las de la v3 y la v4 | — | — | — |
| 3 | `…/3.json` | **Histórico auténtico**: generado por Room/KSP sobre el commit `1a17b491c2a30334bdbda6f3babbcfa2c4ca31a8` (v3 distribuida), recuperado por la auditoría independiente de Codex | `32a6e958badc613142b6b8920ec7611a` | `6c67f70849278a6de558fe0a674eb50bdb8416b6a8ad97afd27d10bb979b6296` | 38483 |
| 4 | `…/4.json` | Generado por Room/KSP sobre el commit `64e8ec400769572eb5f22995a0b08a411d0a1df3` (modelo v4 actual), recuperado por Codex | `d7996e1a59d6e18ca49dae537a03cc80` | `a7ed3adc43689fae3fea97756f37c46aacb2392e581e0f29b8bdf3cffebc864a` | 38695 |

- **No regenerar el 3.json** con el modelo actual (es v4): el `3.json` incorrecto de la ronda b62f065 tenía el hash de la v4. 
- Diferencia de modelo 3→4: solo la clave primaria de `sync_revs` (`userId` añadido). Las demás 13 tablas son idénticas (comprobado en `RoomSchemaFilesTest`).
- Procedencia detallada (clones, `GymDatabase_Impl`, APK de pruebas de la 3.ª auditoría): `PROCEDENCIA_SCHEMAS.md` de Codex; acredita los esquemas usados en la auditoría, **no** la versión instalada en ningún teléfono.
- El `build.gradle` empaqueta esta carpeta como assets de `androidTest` (`androidTest.assets.srcDirs += files("$projectDir/schemas")`), que es lo que lee `MigrationTestHelper`.
- Guardas: `SchemaFilesGuardTest` (app, JVM) y `RoomSchemaFilesTest` (syncCore: SHA-256, identidad, y migración 3→4 sobre SQLite real contra el DDL del esquema 4).
