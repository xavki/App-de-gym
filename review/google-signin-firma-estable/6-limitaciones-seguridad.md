# Limitaciones de seguridad que siguen existiendo (workflow de firma estable)

Qué mejora respecto a la primera versión: la compilación (que ejecuta código del repositorio) ya no tiene
acceso al keystore; el trabajo con la clave no descarga el repositorio, ni ejecuta Gradle, ni tests; solo
procesa la APK con `apksigner`. Aun así, queda lo siguiente.

## Riesgos residuales
1. **La configuración del entorno es manual y no se puede comprobar desde el código.** Si el secreto está en el
   entorno `apk-signing` con la regla «solo master», otras ramas no pueden leerlo. Si se deja también como
   secreto de repositorio, cualquier rama con un workflow modificado podría leerlo. Hay que borrar ese duplicado.
2. **Quien pueda escribir en `master` o administrar el repositorio puede obtener la clave** (editando el workflow,
   cambiando las reglas del entorno o modificando la variable `EXPECTED_DEBUG_CERT_SHA256`). Mitigación:
   protección de la rama `master`, revisores obligatorios en el entorno y repositorio privado.
3. **«Aprobado» significa «revisado por una persona».** El commit aprobado se compila sin secretos, pero su APK
   resultante se firma con la clave. Un commit malicioso que se apruebe por error produciría una APK maliciosa
   firmada con tu huella. La lista `.github/approved-commits.txt` es la barrera, no un análisis automático.
4. **`apksigner` procesa una APK que sale de un trabajo que ejecutó código del repositorio.** Es un análisis de
   un fichero no confiable en el trabajo que tiene la clave. El riesgo es bajo (herramienta oficial de Google,
   sin repositorio ni Gradle en ese trabajo), pero no es nulo.
5. **La contraseña del keystore es la pública `android`.** El único secreto es el fichero. Quien lo obtenga puede
   firmar apps aceptadas por Firebase/Google Sign-In con esa huella. Recomendación a futuro: separar la clave de
   pruebas de la de producción y rotar la clave si hubiera sospecha de filtración.
6. **Acciones de terceros fijadas a SHA completos, pero a mano.** Las versiones se verificaron contra las etiquetas
   oficiales de cada repositorio (v4.3.1, v4.9.1, v4.4.4, v4.6.2 y v4.3.0). No se actualizan solas: hay que
   revisarlas periódicamente. Las acciones usan Node 20, que GitHub está retirando.
7. **El análisis del texto de `apksigner` puede dejar de coincidir con versiones nuevas.** El workflow falla de
   forma segura (no publica) y muestra la salida completa en el log para que se pueda ajustar. No se ha
   ejecutado contra un `apksigner` real.
8. **El artefacto final es descargable por quien tenga acceso al repositorio.** La APK debug contiene la
   configuración pública de Firebase. En un repositorio público la puede descargar cualquier usuario de GitHub
   con sesión iniciada. No contiene claves privadas.
9. **El inicio de sesión por correo y contraseña** (`LoginScreen.kt`) sigue mostrando el mensaje original de
   algunas excepciones de Firebase. No se ha tocado, por estar fuera de alcance.
10. **Hasta la primera ejecución real en Actions** ni el workflow ni los tests de Android se han ejecutado en
    su entorno de destino.
