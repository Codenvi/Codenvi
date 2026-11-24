// Crockford Base32 (alphabet 0-9 A-Z without I, L, O, U). Bits are taken MSB-first and the final group is
// padded with zero bits. Decoding is case-insensitive, maps I/L to 1 and O to 0, and ignores hyphens and
// whitespace. See docs/crypto.md.
export const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

export function encode(data: Uint8Array): string {
  let out = "";
  let buffer = 0;
  let bits = 0;
  for (const b of data) {
    for (let i = 7; i >= 0; i--) {
      buffer = (buffer << 1) | ((b >> i) & 1);
      if (++bits === 5) {
        out += ALPHABET[buffer];
        buffer = 0;
        bits = 0;
      }
    }
  }
  if (bits > 0) out += ALPHABET[buffer << (5 - bits)];
  return out;
}

/** Throws on characters outside the alphabet. */
export function normalize(text: string): string {
  let out = "";
  for (const raw of text) {
    if (raw === "-" || /\s/.test(raw)) continue;
    let c = raw.toUpperCase();
    if (c === "I" || c === "L") c = "1";
    else if (c === "O") c = "0";
    if (!ALPHABET.includes(c)) throw new Error(`Invalid character '${raw}'.`);
    out += c;
  }
  return out;
}
