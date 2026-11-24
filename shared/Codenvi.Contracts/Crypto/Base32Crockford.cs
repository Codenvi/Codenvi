using System.Text;

namespace Codenvi.Contracts.Crypto;

/// <summary>
/// Crockford Base32 (alphabet 0-9 A-Z without I, L, O, U). Bits are taken MSB-first and the
/// final group is padded with zero bits. Decoding is case-insensitive, maps I/L to 1 and O to 0,
/// and ignores hyphens and whitespace. See docs/crypto.md.
/// </summary>
public static class Base32Crockford
{
    public const string Alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    public static string Encode(ReadOnlySpan<byte> data) => Encode(data, data.Length * 8);

    /// <summary>Encodes only the first <paramref name="bitCount"/> bits of <paramref name="data"/>.</summary>
    public static string Encode(ReadOnlySpan<byte> data, int bitCount)
    {
        if (bitCount < 0 || bitCount > data.Length * 8) throw new ArgumentOutOfRangeException(nameof(bitCount));
        var sb = new StringBuilder((bitCount + 4) / 5);
        int buffer = 0, bits = 0, consumed = 0;
        foreach (var b in data)
        {
            for (int i = 7; i >= 0 && consumed < bitCount; i--, consumed++)
            {
                buffer = (buffer << 1) | ((b >> i) & 1);
                bits++;
                if (bits == 5) { sb.Append(Alphabet[buffer]); buffer = 0; bits = 0; }
            }
        }
        if (bits > 0) sb.Append(Alphabet[buffer << (5 - bits)]);
        return sb.ToString();
    }

    /// <summary>Decodes to exactly <paramref name="byteCount"/> bytes. Extra trailing padding bits must be zero.</summary>
    public static byte[] Decode(string text, int byteCount)
    {
        var symbols = Normalize(text);
        int needed = (byteCount * 8 + 4) / 5;
        if (symbols.Length != needed) throw new FormatException($"Expected {needed} Base32 characters, got {symbols.Length}.");
        var result = new byte[byteCount];
        int buffer = 0, bits = 0, index = 0;
        foreach (var c in symbols)
        {
            buffer = (buffer << 5) | Value(c);
            bits += 5;
            if (bits >= 8)
            {
                bits -= 8;
                if (index < byteCount) result[index++] = (byte)(buffer >> bits);
                buffer &= (1 << bits) - 1;
            }
        }
        if (buffer != 0) throw new FormatException("Non-zero padding bits.");
        return result;
    }

    public static string Normalize(string text)
    {
        var sb = new StringBuilder(text.Length);
        foreach (var raw in text)
        {
            if (raw == '-' || char.IsWhiteSpace(raw)) continue;
            var c = char.ToUpperInvariant(raw);
            c = c switch { 'I' or 'L' => '1', 'O' => '0', _ => c };
            if (Alphabet.IndexOf(c) < 0) throw new FormatException($"Invalid character '{raw}'.");
            sb.Append(c);
        }
        return sb.ToString();
    }

    public static string Group(string symbols, int size = 4)
    {
        var sb = new StringBuilder();
        for (int i = 0; i < symbols.Length; i += size)
        {
            if (i > 0) sb.Append('-');
            sb.Append(symbols.AsSpan(i, Math.Min(size, symbols.Length - i)));
        }
        return sb.ToString();
    }

    private static int Value(char c)
    {
        int v = Alphabet.IndexOf(c);
        if (v < 0) throw new FormatException($"Invalid character '{c}'.");
        return v;
    }
}
