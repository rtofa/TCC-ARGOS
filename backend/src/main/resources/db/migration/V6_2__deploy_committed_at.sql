-- Adiciona timestamp opcional do commit (committed_at) para cálculo exato do Lead Time for Changes (DORA).
ALTER TABLE deploys ADD COLUMN committed_at TIMESTAMPTZ NULL;
