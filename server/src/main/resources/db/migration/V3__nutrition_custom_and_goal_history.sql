-- Nutrición, segunda parte: comidas personalizadas e historial de objetivos.
-- Solo añade cosas (una tabla, una restricción y relaja un NOT NULL); no borra
-- datos ni toca las tablas de sincronización.

-- 1) Comidas personalizadas: un registro sin meal_code es una comida libre, con
--    nombre y valores por ración introducidos a mano (ya existen las columnas).
ALTER TABLE nutrition_logs ALTER COLUMN meal_code DROP NOT NULL;
ALTER TABLE nutrition_logs ADD CONSTRAINT nutrition_logs_meal_name_check
    CHECK (char_length(btrim(meal_name)) BETWEEN 1 AND 100);

-- 2) Historial de objetivos: cada fila vale desde effective_from hasta la
--    siguiente fila del mismo usuario. Los objetivos de un día D son los de la fila
--    con el mayor effective_from <= D; si no hay ninguna, los iniciales del plan.
--    nutrition_goals se mantiene como "objetivos actuales" (compatibilidad).
CREATE TABLE nutrition_goal_history (
    user_id         TEXT         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    effective_from  DATE         NOT NULL,
    kcal            INTEGER      NOT NULL CHECK (kcal BETWEEN 500 AND 10000),
    protein_g       NUMERIC(6,1) NOT NULL CHECK (protein_g BETWEEN 0 AND 1000),
    fat_g           NUMERIC(6,1) NOT NULL CHECK (fat_g BETWEEN 0 AND 1000),
    carbs_g         NUMERIC(6,1) NOT NULL CHECK (carbs_g BETWEEN 0 AND 1500),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, effective_from)
);

-- Objetivos ya guardados con V2: valen desde el día en que se guardaron (hora de
-- Madrid, la zona por defecto). Antes de ese día se usaban los iniciales del plan.
INSERT INTO nutrition_goal_history (user_id, effective_from, kcal, protein_g, fat_g, carbs_g, created_at, updated_at)
SELECT user_id, (updated_at AT TIME ZONE 'Europe/Madrid')::date, kcal, protein_g, fat_g, carbs_g, created_at, updated_at
  FROM nutrition_goals;
