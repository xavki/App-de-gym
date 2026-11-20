# Renovación de la app Android (rama `feature/room-offline-first`)

Sin cambios en Room (esquema v2 intacto, sin migraciones), ni en la sincronización, el servidor o el panel.
Nutrición no existe en Android: vive solo en el panel web.

## Cronómetro de descanso

| Antes | Causa | Ahora |
|---|---|---|
| Se paraba al cerrar el diálogo o cambiar de pantalla | La cuenta atrás era un `LaunchedEffect` del diálogo | `RestTimer` vive en el ViewModel; reloj monotónico (`elapsedRealtime`) |
| No se reiniciaba bien | Botón con 60 s fijos; al pulsar ▶ con 00:00 terminaba al instante | Cada descanso nuevo empieza desde la duración configurada y sustituye al anterior |
| Sin sonido | Solo vibraba | Sonido de notificación + vibración; con la app en segundo plano, alarma → notificación con sonido |
| Duración olvidada al cerrar la app | Preferencias solo en memoria | `GymFlowPrefs`: `rest_seconds`, `auto_rest_timer` |

- Un aviso por descanso: `RestTimer.tick()` devuelve `true` una sola vez; si la app estaba en segundo
  plano, el aviso lo da la alarma (`RestAlert` + `RestAlarmReceiver`) y no se repite al volver.
- Sonido con uso de audio "notificación": respeta silencio, vibración y "No molestar".
- Sin permiso de alarmas exactas (Android 12+), el aviso en segundo plano puede llegar con retraso.
- En la barra superior del entreno se ve el tiempo restante (chip); el diálogo tiene −15 s, pausa,
  +15 s, reiniciar y cancelar.

## Otros errores corregidos
- **Entreno perdido al girar la pantalla**: el ViewModel se creaba con `remember {}` dentro de `setContent`.
  Ahora `viewModels()` (sobrevive a la recreación de la actividad). Si el sistema mata el proceso, el entreno
  en curso sigue sin guardarse (ver "Pendiente").
- **Buscador**: texto, filtros, selección y posición se perdían al abrir el detalle. Ahora están en
  `ExerciseSearchState` (ViewModel). Solo se limpian con × o "Limpiar". El filtrado solo se recalcula
  cuando cambian el texto, los filtros o el catálogo.
- **Dos campos de notas** en la pantalla de entreno escribiendo en el mismo dato: queda uno.
- **Solapamiento con la barra del sistema**: la barra inferior propia no tenía en cuenta los insets.
  Ahora `NavigationBar` de Material 3; teclado con `adjustResize` + inset del IME descontando la barra.

## Ejercicios en español
- El nombre guardado (`exerciseName`, en inglés para el catálogo) es la referencia de entrenos y rutinas:
  **no se cambia**. `assets/exercise_names_es.json` traduce al mostrar y al buscar (873 del catálogo + 9 de
  plantillas). La búsqueda ignora tildes y acepta el nombre antiguo en inglés.
- Personalizados: se muestran tal como los escribió el usuario.
- Grupo "Stretching" → "Estiramientos"; equipo "Foam Roller" → "Rodillo de espuma".
- **Pendiente de revisión manual**: las **instrucciones** de los 873 ejercicios siguen en inglés (vienen de
  Firestore sin `instructionsEs`). Nombres a revisar por ser términos propios: ejercicios de strongman
  (Atlas, Conan, Yoke…), "Groiners", "Spell caster", "Otis-up", "Landmine 180".

## Navegación y tema
- Cuatro secciones: **Inicio**, **Entrenar** (rutinas, entreno activo, plantillas), **Progreso**, **Perfil**.
  Calendario está en Perfil (mismos datos y alarmas).
- Tema claro por defecto, oscuro desde Perfil → Ajustes (`app_theme_dark`). No sigue al sistema.
  Ventana de arranque clara (sin destello negro); el tema guardado se aplica antes del primer fotograma de
  Compose. Con el tema oscuro elegido, la ventana de arranque del sistema (antes de la app) sigue siendo clara.
- Colores en `Colors.kt` (`LightPalette` / `DarkPalette`); se conservan los nombres antiguos
  (`BackgroundDark`, `AccentCyan`…) como alias del tema para no reescribir todas las pantallas.
- Idioma fijado a español (se quitó el selector; `values-en` sigue en el proyecto sin usarse).

## Progreso (estadísticas en el móvil, sin servidor)
`TrainingStats.kt`, mismas reglas que el panel (`panel/src/stats.ts`):
- Serie efectiva: no es calentamiento y tiene peso, reps o tiempo. Volumen = peso × reps.
- 1RM estimado: Brzycki con series de 1 a 12 reps. Se muestra separado del peso máximo real.
- Grupo muscular: grupo principal del ejercicio en el catálogo (o el del personalizado); cada serie
  cuenta una vez; sin grupo → "Sin grupo".
- Periodos: semana, mes, 3 meses, año, todo. Volumen por semana (por mes si el periodo es largo).

## Pruebas
`./gradlew :app:testDebugUnitTest` (JVM): `RestTimerTest`, `ExerciseNamesTest`, `ExerciseSearchStateTest`,
`TrainingStatsTest` y el `SyncContractTest` existente. Los tests instrumentados (`androidTest`: migraciones,
sincronización) necesitan dispositivo o emulador.

## Entreno en curso recuperable
- Borrador local (`ActiveWorkoutStore`, archivo privado `active_workout.json`, escritura atómica), no en Room:
  un entreno a medias no se sincroniza ni requiere migración. Se guarda al cambiar series, notas o ejercicio
  (agrupado cada 0,8 s) y en el acto al pasar a segundo plano.
- Se recupera al abrir la app con el mismo usuario; la duración se recalcula desde la hora de inicio.
- Finalizar guarda en Room con el id del borrador (repetirlo no duplica) y borra el borrador antes;
  "Descartar" (barra del entreno o aviso en Inicio) lo elimina sin guardar nada.
- Corregido: marcar una serie como hecha o cambiar su tipo no llegaba al entreno guardado si no cambiaba
  el número de series (la lista se copiaba solo al cambiar de tamaño).

## Instrucciones en español
- `assets/exercise_instructions_es.json`: **868 de 873** traducidas (todas las que tienen original). Las 5 sin
  instrucciones en el catálogo (Iron Cross, One-Arm Kettlebell Swings, Push Press, Side Bridge, Side Jackknife) no
  se inventan. La clave es el nombre original; el texto inglés del catálogo sigue en Room como respaldo.
- `assets/exercise_instructions_review_es.json`: 112 indicaciones dudosas, anticuadas o con posible riesgo
  (regla rodilla/punta del pie, "espalda arqueada" en pesos muertos, press/jalón tras nuca o al cuello,
  textos incompletos o con errores del original, calorías estimadas). La ficha muestra
  "Pendiente de revisión: …" bajo esas instrucciones.

## Borradores (auditoría)
- Finalizar en dos pasos: "pendiente de guardar" en disco → Room → se borra; si Room falla o Android cierra la
  app, se guarda al reabrir (mismo id, no duplica, no recrea entrenos borrados).
- Doble pulsación de Finalizar ignorada (antes duplicaba con id nuevo).
- Un archivo de borrador por usuario; el de versiones anteriores (`active_workout.json`) se recupera.
- Copia `.bak` de la versión anterior; archivo dañado apartado como `.corrupt`; nulos rellenados.
- Borrador olvidado >6 h: la duración no suma el tiempo fuera.

## Botón "atrás" del sistema
- No había `BackHandler`: el atrás (3 botones o gesto) cerraba la app desde cualquier pantalla interna.
  Ahora hace lo mismo que la flecha de cada pantalla; en Inicio, desde otra pestaña vuelve a Inicio.

## Mapa muscular (Progreso)
- Figuras frontal y posterior dibujadas para la app (`MuscleAtlas.kt`, trazados propios, sin recursos
  externos ni licencias de terceros). Mitad izquierda definida y la derecha en espejo; tema claro y oscuro.
- Regiones con color: pecho, espalda (trapecio, dorsal, infraespinoso, lumbares), hombros, bíceps, tríceps,
  antebrazos, abdominales (recto y oblicuos), glúteos, cuádriceps, isquiotibiales y gemelos. Sin seguimiento
  (gris fijo): aductores, tibial, cabeza, manos, articulaciones. El cardio no se dibuja: se indica aparte.
- Cálculo (`MuscleActivity.kt`), se conserva: últimos 7 días e intensidad = series del grupo / series del grupo
  con más; color = gris → azul con la escala anterior (0,25 + 0,75 · intensidad).
- Errores corregidos del cálculo anterior:
  - Asignaba grupos por palabras del nombre y un ejercicio sumaba en varios: "Leg Press" y "Military Press" →
    pecho; "Lateral Raise" → gemelos; todo "curl" (bíceps, muñeca, femoral) → bíceps e isquios; "Leg Extensions"
    → tríceps; nombres en español ("Remo con mancuerna", "Zancadas") → nada. Ahora cada ejercicio suma en un
    solo grupo: su músculo principal en el catálogo (primero de `musclesUsed`), si no su `mainGroup`; los que no
    están en el catálogo usan las palabras de antes, ganando la más larga ("leg press" antes que "press").
  - Contaba calentamientos y series con `isCompleted || weight > 0`; ahora usa la serie efectiva de
    `TrainingStats` (la misma que "Series por grupo muscular").
  - El cardio entraba en la escala y podía apagar los músculos; ahora va aparte.
  - Los 7 días eran "ahora − 168 h"; ahora son los de "Semana" en Progreso (hoy y los 6 anteriores,
    `TrainingStats.inPeriod`): un entreno del octavo día natural salía en el mapa y no en las estadísticas.
- Coherencia: series del mapa + cardio + "sin grupo" = total de "Series por grupo muscular" en "Semana"
  (test `MuscleActivityTest`). El mapa es más fino: "Piernas" se reparte en cuádriceps, isquios, glúteos
  y gemelos según el músculo principal.
- Ejercicios del catálogo sin grupo dibujable (cuentan como "Sin grupo muscular"): 42 de 973, con
  músculo principal Aductores, Abductores, Cuello, "Full body" o el genérico "Piernas" (p. ej. "Zancadas").
- 3 ejercicios cuyo músculo principal no coincide con su mainGroup (dato del catálogo, no inventado):
  "Curl inverso" → antebrazos, "Remo al cuello" → espalda (trapecio), "Peso muerto rumano (Espalda)" →
  isquiotibiales.

## Prueba de proceso muerto
- `WorkoutDraftTest.fase1…`/`fase2…` (`@ProcesoReal`) necesitan que el proceso muera entre las dos: en la
  batería normal comparten proceso, el orden de JUnit no está garantizado y otros tests borran el borrador.
  La fase 2 comprueba además que corre en otro PID.
- Batería: `adb -s <emulador> shell am instrument -w -e notAnnotation com.gymflow.ProcesoReal com.gymflow.test/androidx.test.runner.AndroidJUnitRunner`
- Proceso muerto: `app/scripts/prueba-proceso-muerto.sh <emulador>` (se niega a ejecutarse en un móvil real).

## Versión
- versionCode 2 / versionName 1.1 (mapa muscular). Mismo applicationId y firma debug; Room sigue en v2.

## Cambio de hora (días naturales)
- `TrainingStats` usaba días de 24 h: con el cambio de hora la "Semana" (y mes, 3 meses, año) empezaba a la
  01:00 o a las 23:00 del día anterior, el día de 25 h acababa a las 23:00 y "entrenos por semana" contaba un día
  de más. Ahora suma y resta días con `Calendar` en la zona del móvil (`daysBefore`, `endOfDay`, `calendarDays`).
- Mismo error corregido en la racha (`TrainingStats.streak`, antes en el ViewModel restando 24 h: se cortaba al
  cruzar el cambio) y en la gráfica de Récords (agrupaba por día UTC: un entreno a las 00:30 caía el día anterior).
- El mapa muscular usa el mismo `inPeriod`: Progreso y mapa siguen coincidiendo.
- Tests: `TrainingStatsZonaHorariaTest` (Europe/Madrid, 29/03 y 25/10/2026; semana, mes, 3 meses y año).
- Diferencia con el panel web (`panel/src/stats.ts`, sin tocar): sus rangos son 30/90/180/365 días (sin
  semana) y calcula el inicio como `startOfDay(fin − (días − 1) × 24 h)`, que en la semana posterior al cambio
  de octubre deja un día menos. Las cifras de un mismo rango pueden diferir en ese borde.

## Pendiente
- Mismo patrón de 24 h fuera de estadísticas (no corregido aquí): recurrencia "cada N días" del calendario
  (`isRecurringOnDay`) y alarmas programadas (`NotificationHelper`): tras un cambio de hora pueden marcar el día
  equivocado o sonar una hora desplazadas.
- Revisión visual en dispositivo real de todas las pantallas en claro y oscuro.
