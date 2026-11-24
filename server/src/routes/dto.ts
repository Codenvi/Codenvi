import { ms } from "../db/client";
import { toB64 } from "../lib/http";

export interface InstallRow {
  id: string; kind: string; name: string; app_version: string; app_build: number; os_version: string | null;
  device_model: string | null; last_ip: string | null; paired_at: Date; last_seen_at: Date; revoked_at: Date | null;
}
export interface DeviceRow {
  id: string; install_id: string; name: string; key_version: number; key_check: Buffer;
  last_online_at: Date | null; created_at: Date; deleted_at: Date | null;
}
export interface ProjectRow {
  id: string; device_id: string; enc_meta: Buffer; default_session_id: string | null; sort_order: number; created_at: Date; updated_at: Date;
}
export interface SessionRow { id: string; project_id: string; enc_meta: Buffer; created_at: Date; updated_at: Date }

export const INSTALL_COLUMNS =
  "id, kind, name, app_version, app_build, os_version, device_model, host(last_ip) AS last_ip, paired_at, last_seen_at, revoked_at";
export const DEVICE_COLUMNS = "id, install_id, name, key_version, key_check, last_online_at, created_at, deleted_at";
export const PROJECT_COLUMNS = "id, device_id, enc_meta, default_session_id, sort_order, created_at, updated_at";
export const SESSION_COLUMNS = "id, project_id, enc_meta, created_at, updated_at";

export const installDto = (r: InstallRow, self: string) => ({
  id: r.id, kind: r.kind, name: r.name, appVersion: r.app_version, appBuild: r.app_build, osVersion: r.os_version,
  deviceModel: r.device_model, lastIp: r.last_ip, pairedAtMs: r.paired_at.getTime(), lastSeenAtMs: r.last_seen_at.getTime(),
  revoked: r.revoked_at !== null, isSelf: r.id === self,
});

export const deviceDto = (r: DeviceRow, online: boolean) => ({
  id: r.id, installId: r.install_id, name: r.name, keyVersion: r.key_version, keyCheck: toB64(r.key_check),
  online, lastOnlineAtMs: ms(r.last_online_at), createdAtMs: r.created_at.getTime(),
});

export const projectDto = (r: ProjectRow) => ({
  id: r.id, deviceId: r.device_id, encMeta: toB64(r.enc_meta), defaultSessionId: r.default_session_id,
  sortOrder: r.sort_order, createdAtMs: r.created_at.getTime(), updatedAtMs: r.updated_at.getTime(),
});

export const sessionDto = (r: SessionRow) => ({
  id: r.id, projectId: r.project_id, encMeta: toB64(r.enc_meta), createdAtMs: r.created_at.getTime(), updatedAtMs: r.updated_at.getTime(),
});
