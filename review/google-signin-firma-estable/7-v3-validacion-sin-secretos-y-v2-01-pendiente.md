# v3: validación sin secretos y requisitos pendientes de la firma (V2-01)

## Dos workflows, dos niveles de confianza
| Workflow | Para qué | Secretos / entorno | Cuándo corre |
|---|---|---|---|
| `android-validate.yml` | Compilar y probar. **No firma ni publica la APK.** | Ninguno. No necesita el entorno `apk-signing` ni `DEBUG_KEYSTORE_BASE64`. | Al hacer push a `review/**` y a mano. Falla si fallan los tests o la compilación. |
| `android-debug-apk.yml` | Firmar la APK con la clave debug estable. **Desactivado por defecto**: no hace nada hasta que se cumpla V2-01. | Entorno `apk-signing` (solo trabajo `sign`). | Solo a mano, desde `master`, con un SHA aprobado. |

Por qué dos workflows: un workflow lanzado a mano solo es accesible si está en la rama por defecto, y el de
firma debe quedar restringido a `master`. La validación se ejecuta con el `push` y usa el workflow de la propia
rama de revisión, sin secretos, así que se puede probar la v3 sin tocar `master` ni el entorno de firma.
La validación comprueba `git rev-parse HEAD == github.sha` y no sube ninguna APK (solo informes de tests).

## V2-05: Build Tools
`BUILD_TOOLS_VERSION: "35.0.0"` en el workflow de firma. Se comprueba que `build-tools/35.0.0/apksigner`
existe y es ejecutable en un paso previo, SIN secreto, y otra vez antes de firmar y de verificar. Nunca se elige
la «última» versión instalada; si falta, no se firma.

## V2-01: requisitos que siguen PENDIENTES (no se ha configurado nada)
No se crea el entorno, no se guarda ningún secreto y no se pide ninguna clave en esta fase.
1. **Restricción a `master`:** entorno `apk-signing` con *Deployment branches → solo `master`*. El workflow ya
   exige `github.ref == refs/heads/master`, pero eso no es una barrera si alguien modifica el workflow.
2. **Revisión requerida:** *Required reviewers* en el entorno (tú). Sin aprobación, el trabajo `sign` no arranca.
3. **Protección del workflow y de la lista:** proteger `master` (sin push forzado; PR obligatorio) y añadir un
   `CODEOWNERS` (o regla equivalente) para `.github/workflows/**` y `.github/approved-commits.txt`.
   Un SHA solo se aprueba tras revisarlo.
4. **Sin secretos duplicados:** `DEBUG_KEYSTORE_BASE64` únicamente como secreto del entorno; ninguno de
   repositorio ni de organización con ese nombre.
5. **Huellas esperadas:**
   - SHA-1: `8F:22:5A:7F:7B:38:D5:E3:04:61:FD:34:5E:6E:63:0F:39:0B:56:88`
     (`8f225a7f7b38d5e30461fd345e6e630f390b5688`, ya en `EXPECTED_DEBUG_SHA1`).
   - SHA-256: lo obtienes tú del `debug.keystore` con `keytool` (ver `3-configurar-secreto.md`) y lo guardas en la
     variable `EXPECTED_DEBUG_CERT_SHA256`. No se conoce todavía; hasta entonces la verificación falla (a propósito).

Hasta que 1-5 estén hechos, no se debe lanzar `android-debug-apk.yml`.
