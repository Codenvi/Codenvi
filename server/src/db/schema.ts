import { sql } from "drizzle-orm";
import {
  type AnyPgColumn, bigint, bigserial, boolean, char, check, customType, index, integer, pgTable, text, timestamp, unique, uuid,
} from "drizzle-orm/pg-core";

// Codenvi schema (docs/design.md §6.2). Timestamps are stored as timestamptz, which is UTC.
const bytea = customType<{ data: Buffer; driverData: Buffer }>({ dataType: () => "bytea" });
const inet = customType<{ data: string; driverData: string }>({ dataType: () => "inet" });
const ts = (name: string) => timestamp(name, { withTimezone: true, mode: "date" });

/** One row per paired app instance (a phone or a PC). Also tracks app versions. */
export const installs = pgTable("installs", {
  id: uuid("id").primaryKey(),
  kind: text("kind").notNull(),
  name: text("name").notNull(),
  appVersion: text("app_version").notNull(),
  appBuild: integer("app_build").notNull(),
  osVersion: text("os_version"),
  deviceModel: text("device_model"),
  lastIp: inet("last_ip"),
  pairedAt: ts("paired_at").notNull().defaultNow(),
  lastSeenAt: ts("last_seen_at").notNull().defaultNow(),
  revokedAt: ts("revoked_at"),
}, (t) => [check("installs_kind_check", sql`${t.kind} in ('desktop','android')`)]);

export const installVersionHistory = pgTable("install_version_history", {
  id: bigserial("id", { mode: "number" }).primaryKey(),
  installId: uuid("install_id").notNull().references(() => installs.id, { onDelete: "cascade" }),
  appVersion: text("app_version").notNull(),
  appBuild: integer("app_build").notNull(),
  seenAt: ts("seen_at").notNull().defaultNow(),
});

export const pairingCodes = pgTable("pairing_codes", {
  codeHash: bytea("code_hash").primaryKey(),
  createdBy: text("created_by").notNull(),
  intendedKind: text("intended_kind"),
  expiresAt: ts("expires_at").notNull(),
  usedAt: ts("used_at"),
  usedByInstall: uuid("used_by_install").references(() => installs.id),
}, (t) => [check("pairing_codes_kind_check", sql`${t.intendedKind} in ('desktop','android')`)]);

export const refreshTokens = pgTable("refresh_tokens", {
  id: uuid("id").primaryKey().defaultRandom(),
  installId: uuid("install_id").notNull().references(() => installs.id, { onDelete: "cascade" }),
  tokenHash: bytea("token_hash").notNull().unique(),
  expiresAt: ts("expires_at").notNull(),
  usedAt: ts("used_at"),
  /** The token issued when this one was used (rotation). Lets a lost refresh response be retried. */
  replacedBy: uuid("replaced_by"),
  createdAt: ts("created_at").notNull().defaultNow(),
}, (t) => [index("refresh_tokens_install").on(t.installId)]);

export const serverSettings = pgTable("server_settings", {
  key: text("key").primaryKey(),
  value: text("value").notNull(),
});

/** A desktop PC registered as an executor. */
export const devices = pgTable("devices", {
  id: uuid("id").primaryKey().defaultRandom(),
  installId: uuid("install_id").notNull().references(() => installs.id),
  name: text("name").notNull(),
  keyVersion: integer("key_version").notNull().default(1),
  keyCheck: bytea("key_check").notNull(),
  lastOnlineAt: ts("last_online_at"),
  createdAt: ts("created_at").notNull().defaultNow(),
  deletedAt: ts("deleted_at"),
}, (t) => [index("devices_install").on(t.installId)]);

export const projects = pgTable("projects", {
  id: uuid("id").primaryKey(),
  deviceId: uuid("device_id").notNull().references(() => devices.id, { onDelete: "cascade" }),
  encMeta: bytea("enc_meta").notNull(),
  defaultSessionId: uuid("default_session_id"),
  sortOrder: integer("sort_order").notNull().default(0),
  createdAt: ts("created_at").notNull().defaultNow(),
  updatedAt: ts("updated_at").notNull().defaultNow(),
  deletedAt: ts("deleted_at"),
}, (t) => [index("projects_device").on(t.deviceId)]);

export const sessions = pgTable("sessions", {
  id: uuid("id").primaryKey(),
  projectId: uuid("project_id").notNull().references(() => projects.id, { onDelete: "cascade" }),
  encMeta: bytea("enc_meta").notNull(),
  createdAt: ts("created_at").notNull().defaultNow(),
  updatedAt: ts("updated_at").notNull().defaultNow(),
  deletedAt: ts("deleted_at"),
}, (t) => [index("sessions_project").on(t.projectId)]);

export const messages = pgTable("messages", {
  id: uuid("id").primaryKey(),
  sessionId: uuid("session_id").notNull().references(() => sessions.id, { onDelete: "cascade" }),
  seq: bigint("seq", { mode: "number" }).notNull(),
  role: text("role").notNull(),
  jobId: uuid("job_id"),
  ciphertext: bytea("ciphertext").notNull(),
  clientCreatedAt: ts("client_created_at").notNull(),
  createdAt: ts("created_at").notNull().defaultNow(),
}, (t) => [
  unique("messages_session_id_seq_key").on(t.sessionId, t.seq),
  index("messages_session_role").on(t.sessionId, t.role, t.seq),
  check("messages_role_check", sql`${t.role} in ('user','assistant','system','progress')`),
]);

export const jobs = pgTable("jobs", {
  id: uuid("id").primaryKey(),
  deviceId: uuid("device_id").notNull().references(() => devices.id, { onDelete: "cascade" }),
  projectId: uuid("project_id").notNull().references(() => projects.id, { onDelete: "cascade" }),
  sessionId: uuid("session_id").notNull().references(() => sessions.id, { onDelete: "cascade" }),
  promptMessageId: uuid("prompt_message_id").notNull().references(() => messages.id, { onDelete: "cascade" }),
  sentByInstall: uuid("sent_by_install").references(() => installs.id),
  kind: text("kind").notNull().default("prompt"),
  model: text("model"),
  effort: text("effort"),
  status: text("status").notNull(),
  errorCode: text("error_code"),
  encError: bytea("enc_error"),
  queuedAt: ts("queued_at").notNull().defaultNow(),
  deliveredAt: ts("delivered_at"),
  startedAt: ts("started_at"),
  finishedAt: ts("finished_at"),
}, (t) => [
  index("jobs_device_pending").on(t.deviceId).where(sql`${t.status} in ('queued','delivered')`),
  index("jobs_session").on(t.sessionId),
  check("jobs_kind_check", sql`${t.kind} in ('prompt','control')`),
  check("jobs_status_check", sql`${t.status} in ('queued','delivered','running','succeeded','failed','cancelled','rejected_stale','waiting_login')`),
]);

export const commits = pgTable("commits", {
  id: uuid("id").primaryKey().defaultRandom(),
  projectId: uuid("project_id").notNull().references(() => projects.id, { onDelete: "cascade" }),
  jobId: uuid("job_id").references(() => jobs.id, { onDelete: "set null" }),
  gitHash: char("git_hash", { length: 40 }).notNull(),
  kind: text("kind").notNull(),
  encMeta: bytea("enc_meta").notNull(),
  revertedBy: uuid("reverted_by").references((): AnyPgColumn => commits.id),
  createdAt: ts("created_at").notNull().defaultNow(),
}, (t) => [
  unique("commits_project_id_git_hash_key").on(t.projectId, t.gitHash),
  check("commits_kind_check", sql`${t.kind} in ('task','rollback','baseline')`),
]);

/** Model / effort options shown in the phone dropdowns; editable without an app release. */
export const modelCatalog = pgTable("model_catalog", {
  alias: text("alias").primaryKey(),
  label: text("label").notNull(),
  efforts: text("efforts").array().notNull(),
  enabled: boolean("enabled").notNull().default(true),
  sortOrder: integer("sort_order").notNull().default(0),
});

export const appMinVersions = pgTable("app_min_versions", {
  kind: text("kind").primaryKey(),
  minBuild: integer("min_build").notNull(),
}, (t) => [check("app_min_versions_kind_check", sql`${t.kind} in ('desktop','android')`)]);

export const auditLog = pgTable("audit_log", {
  id: bigserial("id", { mode: "number" }).primaryKey(),
  installId: uuid("install_id"),
  action: text("action").notNull(),
  ip: inet("ip"),
  createdAt: ts("created_at").notNull().defaultNow(),
}, (t) => [index("audit_log_action_time").on(t.action, t.createdAt)]);
