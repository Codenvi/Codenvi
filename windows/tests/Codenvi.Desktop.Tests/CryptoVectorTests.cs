using System.Security.Cryptography;
using System.Text;
using Codenvi.Contracts;
using Codenvi.Contracts.Crypto;
using Xunit;

namespace Codenvi.Desktop.Tests;

/// <summary>Vectors from docs/crypto.md. Android checks the same values in CryptoVectorTest.kt.</summary>
public class CryptoVectorTests
{
    private static readonly byte[] Key = Enumerable.Range(0, 32).Select(i => (byte)i).ToArray();
    private static readonly Guid DeviceId = Guid.Parse("3f2504e0-4f89-41d3-9a0c-cc5d7e0c3b6a");
    private static readonly Guid MessageId = Guid.Parse("9b2f6c1e-1a2b-4c3d-8e9f-0a1b2c3d4e5f");
    private const long CreatedAt = 1764000000000;
    private const string WireHex = "010001000102030405060708090a0b51028004c212aa8c6e9925fe8ed0dab0b5200523bd0224f6cfc84459c05e00ae";

    [Fact]
    public void Base32_matches_vector() =>
        Assert.Equal("000G40R40M30E209185GR38E1W", Base32Crockford.Encode(Enumerable.Range(0, 16).Select(i => (byte)i).ToArray()));

    [Fact]
    public void Base32_roundtrips_and_tolerates_ambiguous_letters()
    {
        var data = RandomNumberGenerator.GetBytes(16);
        var text = Base32Crockford.Encode(data).ToLowerInvariant().Replace('1', 'l').Replace('0', 'o');
        Assert.Equal(data, Base32Crockford.Decode(text, 16));
    }

    [Fact]
    public void Device_key_display_matches_vector() =>
        Assert.Equal("000G-40R4-0M30-E209-185G-R38E-1W81-24GK-2GAH-C5RR-34D1-P70X-3RFG-CC6W", DeviceKey.Format(Key));

    [Fact]
    public void Device_key_parse_detects_typos()
    {
        Assert.Equal(Key, DeviceKey.Parse("000g40r40m30e209185gr38e1w8124gk2gahc5rr34d1p70x3rfgcc6w"));
        Assert.False(DeviceKey.TryParse("000G-40R4-0M30-E209-185G-R38E-1W81-24GK-2GAH-C5RR-34D1-P70X-3RFG-CC6X", out _, out var err));
        Assert.NotNull(err);
    }

    [Fact]
    public void Kcv_matches_vector() => Assert.Equal("868d11c77051b2cb", Convert.ToHexStringLower(E2E.KeyCheck(Key)));

    [Fact]
    public void Hkdf_matches_vector() =>
        Assert.Equal("cc52d91b845455535919bafcddced1c60c49aa235b37fc4c3a9fa4ded3d736ec", Convert.ToHexStringLower(E2E.DeriveEncKey(Key, DeviceId)));

    [Fact]
    public void Aad_matches_spec() => Assert.Equal(
        "v1|3f2504e0-4f89-41d3-9a0c-cc5d7e0c3b6a|message|9b2f6c1e-1a2b-4c3d-8e9f-0a1b2c3d4e5f|p2d|1764000000000",
        Encoding.UTF8.GetString(new AadContext(DeviceId, ObjectTypes.Message, MessageId, Directions.PhoneToDesktop, CreatedAt).ToBytes()));

    [Fact]
    public void Encrypt_matches_vector_and_decrypts()
    {
        var aad = new AadContext(DeviceId, ObjectTypes.Message, MessageId, Directions.PhoneToDesktop, CreatedAt);
        var nonce = Enumerable.Range(0, 12).Select(i => (byte)i).ToArray();
        var wire = E2E.EncryptWithNonce(Key, 1, aad, "{\"text\":\"hello\"}"u8, nonce);
        Assert.Equal(WireHex, Convert.ToHexStringLower(wire));
        Assert.Equal("AQABAAECAwQFBgcICQoLUQKABMISqoxumSX+jtDasLUgBSO9AiT2z8hEWcBeAK4=", Convert.ToBase64String(wire));
        Assert.Equal("{\"text\":\"hello\"}", Encoding.UTF8.GetString(E2E.Decrypt(Key, aad, wire)));
        Assert.Equal(1, E2E.PeekKeyVersion(wire));
    }

    [Fact]
    public void Decrypt_rejects_moved_or_swapped_ciphertext()
    {
        var aad = new AadContext(DeviceId, ObjectTypes.Message, MessageId, Directions.PhoneToDesktop, CreatedAt);
        var wire = E2E.Encrypt(Key, 1, aad, "hi"u8);
        Assert.ThrowsAny<CryptographicException>(() => E2E.Decrypt(Key, aad with { Direction = Directions.DesktopToPhone }, wire));
        Assert.ThrowsAny<CryptographicException>(() => E2E.Decrypt(Key, aad with { ObjectId = Guid.NewGuid() }, wire));
        Assert.ThrowsAny<CryptographicException>(() => E2E.Decrypt(Key, aad with { CreatedAtUnixMs = CreatedAt + 1 }, wire));
        var other = RandomNumberGenerator.GetBytes(32);
        Assert.ThrowsAny<CryptographicException>(() => E2E.Decrypt(other, aad, wire));
        wire[^1] ^= 1;
        Assert.ThrowsAny<CryptographicException>(() => E2E.Decrypt(Key, aad, wire));
    }

    [Fact]
    public void Json_roundtrip()
    {
        var aad = AadContext.Meta(DeviceId, ObjectTypes.ProjectMeta, MessageId);
        var wire = E2E.EncryptJson(Key, 3, aad, new ProjectMeta("Website", "codenvi-dev"));
        Assert.Equal(3, E2E.PeekKeyVersion(wire));
        Assert.Equal(new ProjectMeta("Website", "codenvi-dev"), E2E.DecryptJson<ProjectMeta>(Key, aad, wire));
    }
}
