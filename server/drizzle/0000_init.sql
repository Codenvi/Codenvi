CREATE TABLE "app_min_versions" (
	"kind" text PRIMARY KEY NOT NULL,
	"min_build" integer NOT NULL,
	CONSTRAINT "app_min_versions_kind_check" CHECK ("app_min_versions"."kind" in ('desktop','android'))
);
--> statement-breakpoint
CREATE TABLE "audit_log" (
	"id" bigserial PRIMARY KEY NOT NULL,
	"install_id" uuid,
	"action" text NOT NULL,
	"ip" "inet",
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "commits" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"project_id" uuid NOT NULL,
	"job_id" uuid,
	"git_hash" char(40) NOT NULL,
	"kind" text NOT NULL,
	"enc_meta" "bytea" NOT NULL,
	"reverted_by" uuid,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "commits_project_id_git_hash_key" UNIQUE("project_id","git_hash"),
	CONSTRAINT "commits_kind_check" CHECK ("commits"."kind" in ('task','rollback','baseline'))
);
--> statement-breakpoint
CREATE TABLE "devices" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"install_id" uuid NOT NULL,
	"name" text NOT NULL,
	"key_version" integer DEFAULT 1 NOT NULL,
	"key_check" "bytea" NOT NULL,
	"last_online_at" timestamp with time zone,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"deleted_at" timestamp with time zone
);
--> statement-breakpoint
CREATE TABLE "install_version_history" (
	"id" bigserial PRIMARY KEY NOT NULL,
	"install_id" uuid NOT NULL,
	"app_version" text NOT NULL,
	"app_build" integer NOT NULL,
	"seen_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "installs" (
	"id" uuid PRIMARY KEY NOT NULL,
	"kind" text NOT NULL,
	"name" text NOT NULL,
	"app_version" text NOT NULL,
	"app_build" integer NOT NULL,
	"os_version" text,
	"device_model" text,
	"last_ip" "inet",
	"paired_at" timestamp with time zone DEFAULT now() NOT NULL,
	"last_seen_at" timestamp with time zone DEFAULT now() NOT NULL,
	"revoked_at" timestamp with time zone,
	CONSTRAINT "installs_kind_check" CHECK ("installs"."kind" in ('desktop','android'))
);
--> statement-breakpoint
CREATE TABLE "jobs" (
	"id" uuid PRIMARY KEY NOT NULL,
	"device_id" uuid NOT NULL,
	"project_id" uuid NOT NULL,
	"session_id" uuid NOT NULL,
	"prompt_message_id" uuid NOT NULL,
	"sent_by_install" uuid,
	"kind" text DEFAULT 'prompt' NOT NULL,
	"model" text,
	"effort" text,
	"status" text NOT NULL,
	"error_code" text,
	"enc_error" "bytea",
	"queued_at" timestamp with time zone DEFAULT now() NOT NULL,
	"delivered_at" timestamp with time zone,
	"started_at" timestamp with time zone,
	"finished_at" timestamp with time zone,
	CONSTRAINT "jobs_kind_check" CHECK ("jobs"."kind" in ('prompt','control')),
	CONSTRAINT "jobs_status_check" CHECK ("jobs"."status" in ('queued','delivered','running','succeeded','failed','cancelled','rejected_stale','waiting_login'))
);
--> statement-breakpoint
CREATE TABLE "messages" (
	"id" uuid PRIMARY KEY NOT NULL,
	"session_id" uuid NOT NULL,
	"seq" bigint NOT NULL,
	"role" text NOT NULL,
	"job_id" uuid,
	"ciphertext" "bytea" NOT NULL,
	"client_created_at" timestamp with time zone NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "messages_session_id_seq_key" UNIQUE("session_id","seq"),
	CONSTRAINT "messages_role_check" CHECK ("messages"."role" in ('user','assistant','system','progress'))
);
--> statement-breakpoint
CREATE TABLE "model_catalog" (
	"alias" text PRIMARY KEY NOT NULL,
	"label" text NOT NULL,
	"efforts" text[] NOT NULL,
	"enabled" boolean DEFAULT true NOT NULL,
	"sort_order" integer DEFAULT 0 NOT NULL
);
--> statement-breakpoint
CREATE TABLE "pairing_codes" (
	"code_hash" "bytea" PRIMARY KEY NOT NULL,
	"created_by" text NOT NULL,
	"intended_kind" text,
	"expires_at" timestamp with time zone NOT NULL,
	"used_at" timestamp with time zone,
	"used_by_install" uuid,
	CONSTRAINT "pairing_codes_kind_check" CHECK ("pairing_codes"."intended_kind" in ('desktop','android'))
);
--> statement-breakpoint
CREATE TABLE "projects" (
	"id" uuid PRIMARY KEY NOT NULL,
	"device_id" uuid NOT NULL,
	"enc_meta" "bytea" NOT NULL,
	"default_session_id" uuid,
	"sort_order" integer DEFAULT 0 NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	"deleted_at" timestamp with time zone
);
--> statement-breakpoint
CREATE TABLE "refresh_tokens" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"install_id" uuid NOT NULL,
	"token_hash" "bytea" NOT NULL,
	"expires_at" timestamp with time zone NOT NULL,
	"used_at" timestamp with time zone,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "refresh_tokens_token_hash_unique" UNIQUE("token_hash")
);
--> statement-breakpoint
CREATE TABLE "server_settings" (
	"key" text PRIMARY KEY NOT NULL,
	"value" text NOT NULL
);
--> statement-breakpoint
CREATE TABLE "sessions" (
	"id" uuid PRIMARY KEY NOT NULL,
	"project_id" uuid NOT NULL,
	"enc_meta" "bytea" NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	"deleted_at" timestamp with time zone
);
--> statement-breakpoint
ALTER TABLE "commits" ADD CONSTRAINT "commits_project_id_projects_id_fk" FOREIGN KEY ("project_id") REFERENCES "public"."projects"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "commits" ADD CONSTRAINT "commits_job_id_jobs_id_fk" FOREIGN KEY ("job_id") REFERENCES "public"."jobs"("id") ON DELETE set null ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "commits" ADD CONSTRAINT "commits_reverted_by_commits_id_fk" FOREIGN KEY ("reverted_by") REFERENCES "public"."commits"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "devices" ADD CONSTRAINT "devices_install_id_installs_id_fk" FOREIGN KEY ("install_id") REFERENCES "public"."installs"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "install_version_history" ADD CONSTRAINT "install_version_history_install_id_installs_id_fk" FOREIGN KEY ("install_id") REFERENCES "public"."installs"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "jobs" ADD CONSTRAINT "jobs_device_id_devices_id_fk" FOREIGN KEY ("device_id") REFERENCES "public"."devices"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "jobs" ADD CONSTRAINT "jobs_project_id_projects_id_fk" FOREIGN KEY ("project_id") REFERENCES "public"."projects"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "jobs" ADD CONSTRAINT "jobs_session_id_sessions_id_fk" FOREIGN KEY ("session_id") REFERENCES "public"."sessions"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "jobs" ADD CONSTRAINT "jobs_prompt_message_id_messages_id_fk" FOREIGN KEY ("prompt_message_id") REFERENCES "public"."messages"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "jobs" ADD CONSTRAINT "jobs_sent_by_install_installs_id_fk" FOREIGN KEY ("sent_by_install") REFERENCES "public"."installs"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "messages" ADD CONSTRAINT "messages_session_id_sessions_id_fk" FOREIGN KEY ("session_id") REFERENCES "public"."sessions"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "pairing_codes" ADD CONSTRAINT "pairing_codes_used_by_install_installs_id_fk" FOREIGN KEY ("used_by_install") REFERENCES "public"."installs"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "projects" ADD CONSTRAINT "projects_device_id_devices_id_fk" FOREIGN KEY ("device_id") REFERENCES "public"."devices"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "refresh_tokens" ADD CONSTRAINT "refresh_tokens_install_id_installs_id_fk" FOREIGN KEY ("install_id") REFERENCES "public"."installs"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "sessions" ADD CONSTRAINT "sessions_project_id_projects_id_fk" FOREIGN KEY ("project_id") REFERENCES "public"."projects"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "audit_log_action_time" ON "audit_log" USING btree ("action","created_at");--> statement-breakpoint
CREATE INDEX "devices_install" ON "devices" USING btree ("install_id");--> statement-breakpoint
CREATE INDEX "jobs_device_pending" ON "jobs" USING btree ("device_id") WHERE "jobs"."status" in ('queued','delivered');--> statement-breakpoint
CREATE INDEX "jobs_session" ON "jobs" USING btree ("session_id");--> statement-breakpoint
CREATE INDEX "messages_session_role" ON "messages" USING btree ("session_id","role","seq");--> statement-breakpoint
CREATE INDEX "projects_device" ON "projects" USING btree ("device_id");--> statement-breakpoint
CREATE INDEX "refresh_tokens_install" ON "refresh_tokens" USING btree ("install_id");--> statement-breakpoint
CREATE INDEX "sessions_project" ON "sessions" USING btree ("project_id");