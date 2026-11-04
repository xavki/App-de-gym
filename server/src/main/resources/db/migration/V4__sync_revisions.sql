-- Protocolo de sincronización v2: revisión por fila (control de versiones optimista).
-- Las filas existentes empiezan en la revisión 1. La fecha del móvil (updated_at) deja de decidir quién gana.
ALTER TABLE records ADD COLUMN rev BIGINT NOT NULL DEFAULT 1;
