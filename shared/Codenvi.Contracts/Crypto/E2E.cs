using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Codenvi.Contracts.Crypto;

/// <summary>Object types used in the AAD. Keep identical to Android's <c>ObjectTypes</c>.</summary>
public static class ObjectTypes
{
    public const string Message = "message";          // user prompt / assistant reply
    public const string Progress = "progress";        // live activity line
    public const string ProjectMeta = "project.meta";
    public const string SessionMeta = "session.meta";
    public const string CommitMeta = "commit.meta";
    public const string Control = "control";
    public const string ControlResponse = "control.response";
    public const string JobError = "job.error";
}

/// <summary>Direction marker used in the AAD.</summary>
public static class Directions
{
    public const string PhoneToDesktop = "p2d";
    public const string DesktopToPhone = "d2p";
    /// <summary>Metadata objects that either side may write (names etc.). createdAt is always 0.</summary>
    public const string Meta = "meta";
}

/// <summary>Everything that is bound into the AES-GCM additional data.</summary>
public readonly record struct AadContext(Guid DeviceId, string ObjectType, Guid ObjectId, string Direction, long CreatedAtUnixMs)
{
    public byte[] ToBytes() => Encoding.UTF8.GetBytes(
        $"v1|{DeviceId:D}|{ObjectType}|{ObjectId:D}|{Direction}|{CreatedAtUnixMs}");

    public static AadContext Meta(Guid deviceId, string objectType, Guid objectId) =>
        new(deviceId, objectType, objectId, Directions.Meta, 0);
}

/// <summary>
/// End-to-end encryption (docs/design.md §5). Wire format:
/// version(1)=0x01 | keyVersion(2, big-endian) | nonce(12) | ciphertext | tag(16).
/// </summary>
public static class E2E
{
    public const byte WireVersion = 1;
    public const int NonceSize = 12;
    public const int TagSize = 16;
    public const int HeaderSize = 1 + 2 + NonceSize;
    private static readonly byte[] KcvLabel = "codenvi-kcv-v1"u8.ToArray();
    private static readonly byte[] EncInfo = "codenvi-v1-enc"u8.ToArray();

    /// <summary>KCV = HMAC-SHA256(key, "codenvi-kcv-v1")[0..8].</summary>
    public static byte[] KeyCheck(ReadOnlySpan<byte> deviceKey) => HMACSHA256.HashData(deviceKey, KcvLabel)[..8];

    /// <summary>encKey = HKDF-SHA256(ikm=key, salt=UTF8(deviceId "D" format), info="codenvi-v1-enc", L=32).</summary>
    public static byte[] DeriveEncKey(ReadOnlySpan<byte> deviceKey, Guid deviceId) =>
        HKDF.DeriveKey(HashAlgorithmName.SHA256, deviceKey.ToArray(), 32,
            Encoding.UTF8.GetBytes(deviceId.ToString("D")), EncInfo);

    public static byte[] Encrypt(ReadOnlySpan<byte> deviceKey, int keyVersion, AadContext aad, ReadOnlySpan<byte> plaintext)
        => EncryptWithNonce(deviceKey, keyVersion, aad, plaintext, RandomNumberGenerator.GetBytes(NonceSize));

    /// <summary>Deterministic variant, only for test vectors.</summary>
    public static byte[] EncryptWithNonce(ReadOnlySpan<byte> deviceKey, int keyVersion, AadContext aad,
        ReadOnlySpan<byte> plaintext, ReadOnlySpan<byte> nonce)
    {
        if (keyVersion is < 1 or > ushort.MaxValue) throw new ArgumentOutOfRangeException(nameof(keyVersion));
        var encKey = DeriveEncKey(deviceKey, aad.DeviceId);
        var output = new byte[HeaderSize + plaintext.Length + TagSize];
        output[0] = WireVersion;
        BinaryPrimitives.WriteUInt16BigEndian(output.AsSpan(1, 2), (ushort)keyVersion);
        nonce.CopyTo(output.AsSpan(3, NonceSize));
        using var gcm = new AesGcm(encKey, TagSize);
        gcm.Encrypt(nonce, plaintext,
            output.AsSpan(HeaderSize, plaintext.Length),
            output.AsSpan(HeaderSize + plaintext.Length, TagSize),
            aad.ToBytes());
        CryptographicOperations.ZeroMemory(encKey);
        return output;
    }

    /// <summary>Reads the key version from a wire blob without decrypting.</summary>
    public static int PeekKeyVersion(ReadOnlySpan<byte> wire)
    {
        if (wire.Length < HeaderSize + TagSize || wire[0] != WireVersion) throw new CryptographicException("Bad ciphertext header.");
        return BinaryPrimitives.ReadUInt16BigEndian(wire.Slice(1, 2));
    }

    /// <summary>Decrypts and authenticates. Throws <see cref="CryptographicException"/> on any failure.</summary>
    public static byte[] Decrypt(ReadOnlySpan<byte> deviceKey, AadContext aad, ReadOnlySpan<byte> wire)
    {
        PeekKeyVersion(wire);
        var nonce = wire.Slice(3, NonceSize);
        int ctLen = wire.Length - HeaderSize - TagSize;
        var ct = wire.Slice(HeaderSize, ctLen);
        var tag = wire.Slice(HeaderSize + ctLen, TagSize);
        var encKey = DeriveEncKey(deviceKey, aad.DeviceId);
        var plain = new byte[ctLen];
        try
        {
            using var gcm = new AesGcm(encKey, TagSize);
            gcm.Decrypt(nonce, ct, tag, plain, aad.ToBytes());
        }
        finally { CryptographicOperations.ZeroMemory(encKey); }
        return plain;
    }

    public static byte[] EncryptJson<T>(ReadOnlySpan<byte> deviceKey, int keyVersion, AadContext aad, T value) =>
        Encrypt(deviceKey, keyVersion, aad, JsonSerializer.SerializeToUtf8Bytes(value, CodenviJson.Options));

    public static T DecryptJson<T>(ReadOnlySpan<byte> deviceKey, AadContext aad, ReadOnlySpan<byte> wire) =>
        JsonSerializer.Deserialize<T>(Decrypt(deviceKey, aad, wire), CodenviJson.Options)
        ?? throw new CryptographicException("Empty plaintext.");
}
