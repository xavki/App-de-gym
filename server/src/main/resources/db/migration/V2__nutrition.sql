-- Nutrición: objetivos diarios por usuario, catálogo de comidas del plan y registro
-- de lo que se ha comido. Solo añade tablas; no toca las de sincronización.

-- Objetivos diarios. Si un usuario no tiene fila, el servidor usa los valores
-- iniciales del plan (NutritionRepository.DEFAULT_GOALS). Se guardan tal cual
-- se introducen, sin "corregir" las calorías a partir de los macros.
CREATE TABLE nutrition_goals (
    user_id     TEXT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    kcal        INTEGER      NOT NULL CHECK (kcal BETWEEN 500 AND 10000),
    protein_g   NUMERIC(6,1) NOT NULL CHECK (protein_g BETWEEN 0 AND 1000),
    fat_g       NUMERIC(6,1) NOT NULL CHECK (fat_g BETWEEN 0 AND 1000),
    carbs_g     NUMERIC(6,1) NOT NULL CHECK (carbs_g BETWEEN 0 AND 1500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Comidas base del plan ("Plan de Nutrición - Xavi", 27/10/2026). Valores
-- aproximados tal como vienen en el plan. plan_days: días de la semana en que el
-- menú semanal propone esa comida (ISO: 1 = lunes … 7 = domingo).
CREATE TABLE nutrition_meals (
    code         TEXT PRIMARY KEY,
    category     TEXT         NOT NULL CHECK (category IN ('BREAKFAST', 'LUNCH', 'SNACK', 'DINNER')),
    name         TEXT         NOT NULL,
    ingredients  TEXT         NOT NULL,
    kcal         NUMERIC(7,1) NOT NULL CHECK (kcal >= 0),
    protein_g    NUMERIC(6,1) NOT NULL CHECK (protein_g >= 0),
    fat_g        NUMERIC(6,1) NOT NULL CHECK (fat_g >= 0),
    carbs_g      NUMERIC(6,1) NOT NULL CHECK (carbs_g >= 0),
    plan_days    SMALLINT[]   NOT NULL DEFAULT '{}',
    sort_order   INTEGER      NOT NULL,
    active       BOOLEAN      NOT NULL DEFAULT true
);

INSERT INTO nutrition_meals (code, category, name, ingredients, kcal, protein_g, fat_g, carbs_g, plan_days, sort_order) VALUES
('D1',  'BREAKFAST', 'Avena con proteína y plátano',
 '70 g copos de avena, 1 cacito (30 g) de proteína en polvo, 300 ml leche semidesnatada, 1 plátano (120 g), 15 g crema de cacahuete',
 710, 48, 19, 90, '{1,4,7}', 1),
('D2',  'BREAKFAST', 'Tostadas con huevos y jamón',
 '80 g pan integral (2-3 rebanadas), 3 huevos, 30 g jamón york, 30 g queso fresco batido 0%, 1 cucharadita de aceite de oliva, 1 pieza de fruta',
 590, 38, 25, 57, '{2,5}', 2),
('D3',  'BREAKFAST', 'Yogur griego con avena tostada y frutos rojos',
 '300 g yogur griego natural, 40 g avena + 10 g almendras (tostadas juntas en sartén sin aceite), 100 g frutos rojos (valen congelados), 15 g miel',
 590, 35, 23, 62, '{3,6}', 3),
('C1',  'LUNCH', 'Arroz con pollo y verduras',
 '90 g arroz (en crudo), 180 g pechuga de pollo, 150 g verduras variadas (pimiento, calabacín, cebolla), 10 ml aceite de oliva',
 645, 50, 13, 77, '{1,5}', 4),
('C2',  'LUNCH', 'Pasta integral con atún',
 '90 g pasta integral (en crudo), 2 latas de atún al natural (160 g escurrido), 150 g tomate triturado, 10 aceitunas, 5 ml aceite de oliva',
 585, 53, 11, 64, '{2,6}', 5),
('C3',  'LUNCH', 'Lentejas con pavo',
 '300 g lentejas cocidas, 100 g pechuga de pavo, 150 g verduras (zanahoria, cebolla, pimentón), 40 g pan, 10 ml aceite de oliva',
 695, 58, 14, 88, '{3,7}', 6),
('C4',  'LUNCH', 'Patata al horno con merluza y verduras asadas',
 '300 g patata, 180 g merluza (o salmón, sube algo la grasa), 150 g verduras para asar (calabacín, pimentón, berenjena), 10 ml aceite, 15 g frutos secos',
 610, 42, 21, 66, '{4}', 7),
('M1',  'SNACK', 'Batido de proteína con avena y manzana',
 '1 cacito (30 g) de proteína, 250 ml leche, 30 g avena, 1 manzana o pera (150 g)',
 425, 37, 8, 54, '{1,4,6}', 8),
('M2',  'SNACK', 'Yogur con nueces y plátano',
 '250 g yogur natural, 20 g nueces, 1/2 plátano (60 g), canela al gusto',
 335, 12, 21, 28, '{2,5}', 9),
('M3',  'SNACK', 'Tostada con atún',
 '40 g pan integral, 1 lata de atún al natural (80 g escurrido), tomate, orégano',
 190, 25, 2, 18, '{3,7}', 10),
('Cn1', 'DINNER', 'Tortilla de champiñones con pan',
 '3 huevos, 100 g champiñones, 20 g queso rallado, 60 g pan integral, 5 ml aceite',
 520, 36, 29, 30, '{3,7}', 11),
('Cn2', 'DINNER', 'Pollo con boniato y verduras',
 '180 g pechuga de pollo a la plancha, 200 g boniato asado, 150 g brócoli o judía verde, 10 ml aceite',
 505, 48, 13, 48, '{1,5}', 12),
('Cn3', 'DINNER', 'Merluza con arroz y verduras',
 '180 g merluza al horno, 80 g arroz (en crudo), 150 g verduras al vapor, 10 ml aceite',
 540, 39, 12, 69, '{2,6}', 13),
('Cn4', 'DINNER', 'Revuelto de claras con jamón y aguacate',
 '2 huevos enteros + 3 claras, 30 g jamón serrano, 100 g champiñones, 40 g pan, 1/2 aguacate (75 g)',
 515, 44, 28, 28, '{4}', 14);

-- Comidas registradas. El id lo genera el cliente (UUID) por cada pulsación:
-- si la petición se reintenta llega el mismo id y no se duplica.
-- Se guarda una copia de los valores por ración en el momento de registrar, para
-- que cambiar el catálogo no altere el historial. log_date es el día natural del
-- usuario (zona horaria local), no un instante UTC.
-- Borrado lógico (deleted_at): el registro deja de contar pero no se pierde.
CREATE TABLE nutrition_logs (
    id                 UUID PRIMARY KEY,
    user_id            TEXT         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    log_date           DATE         NOT NULL,
    meal_code          TEXT         NOT NULL REFERENCES nutrition_meals (code),
    meal_name          TEXT         NOT NULL,
    servings           NUMERIC(4,2) NOT NULL CHECK (servings > 0 AND servings <= 10),
    kcal_per_serving   NUMERIC(7,1) NOT NULL CHECK (kcal_per_serving >= 0),
    protein_per_serving NUMERIC(6,1) NOT NULL CHECK (protein_per_serving >= 0),
    fat_per_serving    NUMERIC(6,1) NOT NULL CHECK (fat_per_serving >= 0),
    carbs_per_serving  NUMERIC(6,1) NOT NULL CHECK (carbs_per_serving >= 0),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at         TIMESTAMPTZ
);
CREATE INDEX nutrition_logs_user_date ON nutrition_logs (user_id, log_date) WHERE deleted_at IS NULL;
