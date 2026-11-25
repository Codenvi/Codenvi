-- Model names with their versions, and a "Default" that says what it means (docs/design.md §6.3).
-- The aliases stay the same; they are what `claude --model` receives.
UPDATE model_catalog SET label = 'Default (PC setting)' WHERE alias = 'default';
--> statement-breakpoint
UPDATE model_catalog SET label = 'Sonnet 5.5' WHERE alias = 'sonnet';
--> statement-breakpoint
UPDATE model_catalog SET label = 'Opus 5.5' WHERE alias = 'opus';
--> statement-breakpoint
UPDATE model_catalog SET label = 'Haiku 5.5' WHERE alias = 'haiku';
--> statement-breakpoint
UPDATE model_catalog SET label = 'Fable 5.2 (optional)' WHERE alias = 'fable';
