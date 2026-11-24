/** Remembers which install asked a control request so the response goes back to it. */
const TTL_MS = 10 * 60_000;
const pending = new Map<string, { installId: string; deviceId: string; at: number }>();

export function registerControl(requestId: string, installId: string, deviceId: string): void {
  const cutoff = Date.now() - TTL_MS;
  for (const [k, v] of pending) if (v.at < cutoff) pending.delete(k);
  pending.set(requestId, { installId, deviceId, at: Date.now() });
}

/** Returns the requesting install if the response comes from the device the request was sent to. */
export function completeControl(requestId: string, fromDeviceId: string): string | null {
  const p = pending.get(requestId);
  if (p && p.deviceId === fromDeviceId) {
    pending.delete(requestId);
    return p.installId;
  }
  return null;
}
