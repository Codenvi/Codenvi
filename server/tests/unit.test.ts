import { describe, expect, it } from "vitest";
import { encode, normalize } from "../src/lib/base32";
import { parseClient } from "../src/auth/guard";
import { hashCode, newCode } from "../src/auth/pairing";

describe("Crockford Base32 (docs/crypto.md vector)", () => {
  it("matches the vector", () => {
    expect(encode(Uint8Array.from({ length: 16 }, (_, i) => i))).toBe("000G40R40M30E209185GR38E1W");
  });
  it("tolerates case, ambiguous letters and hyphens", () => {
    expect(normalize("000g-40r4-0m30-e209-185g-r38e-1w")).toBe("000G40R40M30E209185GR38E1W");
    expect(normalize("oOiIlL")).toBe("001111");
  });
  it("rejects characters outside the alphabet", () => {
    expect(() => normalize("ABC!")).toThrow();
    expect(() => normalize("U")).toThrow();
  });
});

describe("pairing codes", () => {
  it("are 128-bit (26 chars) and hash the normalised form", () => {
    const code = newCode();
    expect(code).toHaveLength(26);
    expect(hashCode(code).equals(hashCode(code.toLowerCase()))).toBe(true);
  });
});

describe("X-Codenvi-Client header", () => {
  it.each([
    ["android/1.4.0+42 (Android 14; Pixel 7)", { kind: "android", version: "1.4.0", build: 42, os: "Android 14", model: "Pixel 7" }],
    ["desktop/1.4.0+57 (Windows 11 26100)", { kind: "desktop", version: "1.4.0", build: 57, os: "Windows 11 26100", model: null }],
    ["desktop/1.0.3+3", { kind: "desktop", version: "1.0.3", build: 3, os: null, model: null }],
  ])("parses %s", (header, expected) => {
    expect(parseClient(header)).toEqual(expected);
  });
  it("rejects garbage", () => {
    expect(parseClient("curl/8.0")).toBeNull();
    expect(parseClient(undefined)).toBeNull();
  });
});
