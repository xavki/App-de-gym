# Cómo crear el secreto DEBUG_KEYSTORE_BASE64 (Windows) sin exponer la clave

Objetivo: que GitHub Actions firme la APK con TU `debug.keystore` (la misma huella SHA-1 que tu Samsung).
La clave nunca se sube al repositorio, ni se pega en el chat, ni se envía a ningún servicio salvo a los
Secrets cifrados de GitHub.

## 1. Comprobar el keystore (solo lectura)
En PowerShell, con el JDK de Android Studio o cualquier `keytool`:

    keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android | Select-String "SHA1"

Debe salir `8F:22:5A:7F:7B:38:D5:E3:04:61:E6:E6:30:F3:90:B5:68:8B...` empezando por 8F:22:5A:7F.
(Ya lo comprobaste; esto además confirma que la contraseña estándar `android` y el alias
`androiddebugkey` funcionan, que es lo que el workflow necesita.)

## 2. Copiar el Base64 al portapapeles SIN mostrarlo en pantalla

    [Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android\debug.keystore")) | Set-Clipboard

## 3. Crear el secreto en GitHub
1. Repositorio `xavki/App-de-gym` → Settings → Secrets and variables → Actions.
2. New repository secret.
3. Name: `DEBUG_KEYSTORE_BASE64` (exactamente así).
4. Secret: pega el portapapeles (Ctrl+V). No lo edites.
5. Add secret.

## 4. Limpiar el portapapeles

    Set-Clipboard -Value $null

## 5. Reglas
- Es una clave de DEPURACIÓN, pero quien la tenga puede firmar apps que Google Sign-In aceptaría
  como tuyas. Trátala como secreto.
- No la subas al repo, no la envíes por correo ni la pegues en chats (tampoco en este).
- No uses un "Environment secret" ni "Variable": tiene que ser un Repository secret.
- Los secretos no llegan a workflows de forks. El workflow es solo manual (workflow_dispatch).
- El workflow no imprime el secreto, no usa `set -x`, escribe el fichero con permisos 600 y lo borra
  al terminar (`if: always()`).
- Para retirar el acceso: borra el secreto en la misma pantalla.
