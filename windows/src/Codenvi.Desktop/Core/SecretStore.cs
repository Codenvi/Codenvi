using System.Security.Cryptography;
using System.Text.Json;
using Codenvi.Contracts;

namespace Codenvi.Desktop.Core;

/// <summary>Everything secret, stored with DPAPI (CurrentUser) in secrets.bin (Project.md §8.3).</summary>
public sealed class Secrets
{
    public string? ServerHost { get; set; }
    public string? Pin { get; set; }
    public Guid InstallId { get; set; }
    public string? RefreshToken { get; set; }
    public Guid? DeviceId { get; set; }
    public string? DeviceName { get; set; }
    public int CurrentKeyVersion { get; set; }
    /// <summary>All device key versions (old ones kept so history stays readable).</summary>
    public Dictionary<int, byte[]> Keys { get; set; } = new();
}

public sealed class SecretStore
{
    private static readonly byte[] Entropy = "codenvi-desktop-v1"u8.ToArray();
    private readonly object _lock = new();
    private readonly string _path;
    private Secrets _current;

    public SecretStore() : this(AppPaths.Secrets) { }

    public SecretStore(string path)
    {
        _path = path;
        _current = Load();
    }

    public Secrets Current { get { lock (_lock) return _current; } }

    public bool IsPaired => Current.RefreshToken is not null && Current.ServerHost is not null;
    public bool HasDevice => Current.DeviceId is not null && Current.Keys.Count > 0;

    public byte[]? KeyFor(int version) => Current.Keys.GetValueOrDefault(version);
    public byte[]? CurrentKey => KeyFor(Current.CurrentKeyVersion);

    public void Update(Action<Secrets> change)
    {
        lock (_lock)
        {
            change(_current);
            Save(_current);
        }
    }

    public void Clear() => Update(s =>
    {
        s.ServerHost = null; s.Pin = null; s.RefreshToken = null; s.DeviceId = null; s.DeviceName = null;
        s.CurrentKeyVersion = 0;
        foreach (var k in s.Keys.Values) CryptographicOperations.ZeroMemory(k);
        s.Keys.Clear();
    });

    private Secrets Load()
    {
        if (!File.Exists(_path)) return new Secrets { InstallId = Guid.NewGuid() };
        var protectedBytes = File.ReadAllBytes(_path);
        var plain = ProtectedData.Unprotect(protectedBytes, Entropy, DataProtectionScope.CurrentUser);
        try
        {
            return JsonSerializer.Deserialize<Secrets>(plain, CodenviJson.Options) ?? new Secrets { InstallId = Guid.NewGuid() };
        }
        finally { CryptographicOperations.ZeroMemory(plain); }
    }

    private void Save(Secrets s)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
        var plain = JsonSerializer.SerializeToUtf8Bytes(s, CodenviJson.Options);
        var protectedBytes = ProtectedData.Protect(plain, Entropy, DataProtectionScope.CurrentUser);
        CryptographicOperations.ZeroMemory(plain);
        var tmp = _path + ".tmp";
        File.WriteAllBytes(tmp, protectedBytes);
        File.Move(tmp, _path, overwrite: true);
    }
}
