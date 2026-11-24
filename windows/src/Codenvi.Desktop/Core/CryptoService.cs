using System.Security.Cryptography;
using Codenvi.Contracts;
using Codenvi.Contracts.Crypto;

namespace Codenvi.Desktop.Core;

/// <summary>Device-key operations for this PC (docs/design.md §5).</summary>
public sealed class CryptoService(SecretStore secrets)
{
    public Guid DeviceId => secrets.Current.DeviceId ?? throw new InvalidOperationException("This PC is not registered as a device yet.");
    public int KeyVersion => secrets.Current.CurrentKeyVersion;

    private byte[] CurrentKey => secrets.CurrentKey ?? throw new InvalidOperationException("No device key.");

    public byte[] Encrypt<T>(string objectType, Guid objectId, string direction, long createdAtMs, T value) =>
        E2E.EncryptJson(CurrentKey, KeyVersion, new AadContext(DeviceId, objectType, objectId, direction, createdAtMs), value);

    public byte[] EncryptMeta<T>(string objectType, Guid objectId, T value) =>
        E2E.EncryptJson(CurrentKey, KeyVersion, AadContext.Meta(DeviceId, objectType, objectId), value);

    /// <summary>Decrypts with whichever key version the blob names. Throws CryptographicException on failure.</summary>
    public T Decrypt<T>(string objectType, Guid objectId, string direction, long createdAtMs, byte[] wire)
    {
        var key = KeyForWire(wire);
        return E2E.DecryptJson<T>(key, new AadContext(DeviceId, objectType, objectId, direction, createdAtMs), wire);
    }

    public T DecryptMeta<T>(string objectType, Guid objectId, byte[] wire) =>
        E2E.DecryptJson<T>(KeyForWire(wire), AadContext.Meta(DeviceId, objectType, objectId), wire);

    public bool TryDecryptMeta<T>(string objectType, Guid objectId, byte[] wire, out T? value)
    {
        try { value = DecryptMeta<T>(objectType, objectId, wire); return true; }
        catch (Exception e) when (e is CryptographicException or System.Text.Json.JsonException) { value = default; return false; }
    }

    private byte[] KeyForWire(byte[] wire)
    {
        var version = E2E.PeekKeyVersion(wire);
        return secrets.KeyFor(version) ?? throw new CryptographicException($"Key version {version} is not available on this PC.");
    }

    /// <summary>Phone connection string: pairing code + device ID + key (docs/design.md §4.2).</summary>
    public string PhoneConnectionString(string host, string pairingCode, string? pin) => new ConnectionString
    {
        Host = host,
        PairingCode = pairingCode,
        Pin = pin,
        DeviceId = DeviceId,
        KeyVersion = KeyVersion,
        Key = CurrentKey,
        DeviceName = secrets.Current.DeviceName,
    }.ToString();

    public string KeyOnlyString() => new ConnectionString
    {
        DeviceId = DeviceId, KeyVersion = KeyVersion, Key = CurrentKey, DeviceName = secrets.Current.DeviceName,
    }.ToString();

    public string DisplayKey() => DeviceKey.Format(CurrentKey);
}
