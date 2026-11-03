# Cómo configurar la firma debug estable (Windows) sin exponer la clave

Objetivo: que GitHub Actions firme la APK con TU `debug.keystore` (la misma huella que tu Samsung), de modo
que Google Sign-In y Firebase la reconozcan. La clave nunca se sube al repositorio, ni se pega en el chat, ni
se envía a ningún servicio salvo a los secretos cifrados de GitHub.

Huella SHA-1 esperada (completa, 20 bytes):

    8F:22:5A:7F:7B:38:D5:E3:04:61:FD:34:5E:6E:63:0F:39:0B:56:88

(en minúsculas y sin dos puntos, como aparece en `app/google-services.json`: `8f225a7f7b38d5e30461fd345e6e630f390b5688`)

## 1. Comprobar el keystore (solo lectura)
En PowerShell, con el `keytool` del JDK de Android Studio:

    keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android | Select-String "SHA"

Deben salir una línea `SHA1:` y otra `SHA256:`.
- El SHA1 debe ser exactamente el de arriba (8F:22:5A:7F:…:56:88).
- Anota el **SHA256** (32 bytes, 64 caracteres hex): lo necesitas en el paso 4. Es un dato público.
- Esto confirma además que la contraseña estándar `android` y el alias `androiddebugkey` funcionan.

## 2. Crear el ENTORNO de GitHub `apk-signing` (importante)
El secreto va en un **entorno**, no en un secreto de repositorio. Así solo lo puede leer un trabajo de `master`.

1. Repositorio `xavki/App-de-gym` → Settings → Environments → **New environment**.
2. Nombre exacto: `apk-signing`.
3. **Deployment branches and tags** → *Selected branches and tags* → añade la regla `master`.
4. (Recomendado) **Required reviewers**: añádete a ti. Así cada firma pide una aprobación manual.
5. Guarda.

Si ya existiera un secreto de repositorio llamado `DEBUG_KEYSTORE_BASE64`, **bórralo**: un secreto de repositorio
sería legible desde cualquier rama.

## 3. Copiar el Base64 al portapapeles SIN mostrarlo y guardarlo en el entorno

    [Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android\debug.keystore")) | Set-Clipboard

1. Settings → Environments → `apk-signing` → **Add environment secret**.
2. Name: `DEBUG_KEYSTORE_BASE64` (exactamente así). Value: pega el portapapeles (Ctrl+V). No lo edites.
3. Add secret. Después vacía el portapapeles:

       Set-Clipboard -Value $null

## 4. Guardar el SHA-256 esperado como VARIABLE (dato público)
Settings → Secrets and variables → Actions → pestaña **Variables** → New repository variable:
- Name: `EXPECTED_DEBUG_CERT_SHA256`
- Value: el SHA256 del paso 1 (con o sin dos puntos; el workflow lo normaliza).

Sin esta variable el workflow falla antes de publicar nada.

## 5. Proteger master (recomendado)
Settings → Branches → regla para `master` (sin pushes forzados; PR obligatorio si quieres revisión).
Es en `master` donde viven el workflow y la lista de commits aprobados.

## 6. Cómo se usa después
1. El código a compilar se confirma en su rama y se anota su **SHA completo**.
2. En un PR a `master` se añade ese SHA a `.github/approved-commits.txt` (tras revisarlo) y se fusiona.
3. Actions → *Android debug APK* → Run workflow → desde **master** → `commit_sha` = ese SHA completo.
4. Si usaste *Required reviewers*, apruebas el despliegue en la ejecución.
5. Descargas el artefacto `Gym-Android-1.1-debug` (la APK firmada). Ignora `apk-intermedia-NO-INSTALAR`.

## 7. Reglas
- Es una clave de DEPURACIÓN, pero quien la tenga puede firmar apps que Google Sign-In aceptaría como tuyas.
- No la subas al repo, no la envíes por correo ni la pegues en chats.
- El workflow no imprime el secreto, no usa `set -x`, escribe el fichero con permisos 600 y lo borra al
  terminar el paso de firma. En ese trabajo no se ejecuta Gradle, ni tests, ni código del repositorio.
- Para retirar el acceso: borra el secreto del entorno `apk-signing`.
- Limitaciones de seguridad que siguen existiendo: `6-limitaciones-seguridad.md`.
