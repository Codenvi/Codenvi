using System.Security.Cryptography;

namespace Codenvi.Contracts.Crypto;

/// <summary>
/// 32-byte device key, displayed as 52 Crockford Base32 characters plus a 4-character checksum
/// (first 20 bits of SHA-256(key)), grouped in 4s: 56 characters, 14 groups.
/// </summary>
public static class DeviceKey
{
    public const int KeySize = 32;
    private const int ChecksumBits = 20;

    public static byte[] Generate() => RandomNumberGenerator.GetBytes(KeySize);

    public static string Format(ReadOnlySpan<byte> key)
    {
        if (key.Length != KeySize) throw new ArgumentException("Key must be 32 bytes.", nameof(key));
        var body = Base32Crockford.Encode(key);
        var checksum = Base32Crockford.Encode(SHA256.HashData(key), ChecksumBits);
        return Base32Crockford.Group(body + checksum);
    }

    /// <summary>Compact form (no hyphens) used inside connection strings.</summary>
    public static string FormatCompact(ReadOnlySpan<byte> key) => Format(key).Replace("-", "");

    public static bool TryParse(string text, out byte[] key, out string? error)
    {
        key = Array.Empty<byte>();
        error = null;
        string symbols;
        try { symbols = Base32Crockford.Normalize(text); }
        catch (FormatException e) { error = e.Message; return false; }

        if (symbols.Length != 56) { error = $"The key must have 56 characters (got {symbols.Length})."; return false; }
        byte[] candidate;
        try { candidate = Base32Crockford.Decode(symbols[..52], KeySize); }
        catch (FormatException e) { error = e.Message; return false; }

        var expected = Base32Crockford.Encode(SHA256.HashData(candidate), ChecksumBits);
        if (!CryptographicOperations.FixedTimeEquals(
                System.Text.Encoding.ASCII.GetBytes(expected),
                System.Text.Encoding.ASCII.GetBytes(symbols[52..])))
        {
            error = "Checksum mismatch - check the key for typos.";
            return false;
        }
        key = candidate;
        return true;
    }

    public static byte[] Parse(string text) =>
        TryParse(text, out var key, out var error) ? key : throw new FormatException(error);
}
