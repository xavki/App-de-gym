// Formato del JSON que exporta la app (DataExporter.kt, schemaVersion 1).
// Fechas en ISO-8601 UTC; pesos en kg, medidas en cm, tiempos en segundos.

export type SetType = 'NORMAL' | 'WARMUP' | 'DROP_SET' | 'FAILURE'

export interface ExportSet {
  id: string
  position: number
  type: SetType | string
  weightKg: number
  reps: number
  timeSeconds: number
  rpe: number | null
  completed: boolean
}

export interface ExportWorkoutExercise {
  id: string
  exerciseId: string | null
  name: string
  mainGroup?: string | null
  position: number
  notes: string
  supersetGroup: string | null
  sets: ExportSet[]
}

export interface ExportWorkout {
  id: string
  routineId: string | null
  name: string
  startedAt: string
  durationSeconds: number
  bodyweightKg: number | null
  notes: string
  updatedAt: string
  exercises: ExportWorkoutExercise[]
}

export interface ExportRoutine {
  id: string
  name: string
  updatedAt: string
  exercises: {
    id: string
    exerciseId: string | null
    name: string
    mainGroup?: string | null
    position: number
    notes: string
    sets: { position: number; type: string; targetWeightKg: number; targetReps: number; targetTimeSeconds: number }[]
  }[]
}

export interface ExportBodyMeasurement {
  id: string
  measuredAt: string
  weightKg: number
  bodyFatPct: number
  chestCm: number
  waistCm: number
  hipsCm: number
  bicepCm: number
  thighCm: number
}

export interface GymExport {
  schemaVersion: number
  exportedAt: string
  userId: string
  units: { weight: string; length: string; time: string }
  workouts: ExportWorkout[]
  routines: ExportRoutine[]
  bodyMeasurements: ExportBodyMeasurement[]
  customExercises: { id: string; name: string; mainGroup: string; musclesUsed: string; equipment: string | null; notes: string }[]
  achievements: { key: string; unlockedAt: string }[]
}
