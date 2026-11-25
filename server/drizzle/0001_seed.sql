-- Seed data (docs/design.md §6.3). Upserts, so later migrations can change values.
INSERT INTO model_catalog (alias, label, efforts, enabled, sort_order) VALUES
  ('default', 'Default (PC setting)','{low,medium,high,xhigh,max}', true, 0),
  ('sonnet',  'Sonnet 5.5','{low,medium,high,xhigh,max}', true, 10),
  ('opus',    'Opus 5.5','{low,medium,high,xhigh,max}', true, 20),
  ('haiku',   'Haiku 5.5','{low,medium,high}',           true, 30),
  ('fable',   'Fable 5.2 (optional)','{low,medium,high,xhigh,max}', true, 40)
ON CONFLICT (alias) DO UPDATE
  SET label = EXCLUDED.label, efforts = EXCLUDED.efforts, sort_order = EXCLUDED.sort_order;
--> statement-breakpoint
INSERT INTO app_min_versions (kind, min_build) VALUES
  ('desktop', 0),
  ('android', 0)
ON CONFLICT (kind) DO NOTHING;
--> statement-breakpoint
INSERT INTO server_settings (key, value) VALUES
  ('pairing_enabled', 'true')
ON CONFLICT (key) DO NOTHING;
