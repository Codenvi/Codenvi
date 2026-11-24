using System.Web;
using Codenvi.Contracts.Crypto;

namespace Codenvi.Contracts;

/// <summary>
/// Parses and formats the codenvi:// strings from Project.md §4.1–4.2:
/// <code>
/// codenvi://host[:port]/pair?c=CODE[&amp;pin=SPKI_SHA256_B64URL]
/// codenvi://host[:port]/pair?c=CODE&amp;dev=DEVICEID&amp;kv=N&amp;k=KEY[&amp;dn=NAME]   (phone)
/// codenvi://key?dev=DEVICEID&amp;kv=N&amp;k=KEY[&amp;dn=NAME]                       (key only)
/// </code>
/// </summary>
public sealed record ConnectionString
{
    public const string Scheme = "codenvi";

    public string? Host { get; init; }
    public string? PairingCode { get; init; }
    public string? Pin { get; init; }
    public Guid? DeviceId { get; init; }
    public int? KeyVersion { get; init; }
    public byte[]? Key { get; init; }
    public string? DeviceName { get; init; }

    public bool IsKeyOnly => PairingCode is null && Key is not null;
    public bool CarriesKey => Key is not null && DeviceId is not null && KeyVersion is not null;

    public static ConnectionString Parse(string text)
    {
        text = text.Trim();
        if (!text.StartsWith(Scheme + "://", StringComparison.OrdinalIgnoreCase))
            throw new FormatException("Not a codenvi:// connection string.");
        var uri = new Uri(text);
        var q = HttpUtility.ParseQueryString(uri.Query);

        Guid? dev = q["dev"] is { } d ? Guid.Parse(d) : null;
        int? kv = q["kv"] is { } v ? int.Parse(v) : null;
        byte[]? key = q["k"] is { } k ? DeviceKey.Parse(k) : null;

        if (uri.Host.Equals("key", StringComparison.OrdinalIgnoreCase) && (uri.AbsolutePath is "" or "/"))
        {
            if (dev is null || kv is null || key is null) throw new FormatException("Key string is missing dev, kv or k.");
            return new ConnectionString { DeviceId = dev, KeyVersion = kv, Key = key, DeviceName = q["dn"] };
        }

        if (!uri.AbsolutePath.Equals("/pair", StringComparison.OrdinalIgnoreCase))
            throw new FormatException("Unknown connection string path.");
        var code = q["c"] ?? throw new FormatException("Pairing code missing.");
        if ((key is null) != (dev is null) || (key is null) != (kv is null))
            throw new FormatException("dev, kv and k must appear together.");

        return new ConnectionString
        {
            Host = uri.IsDefaultPort ? uri.Host : $"{uri.Host}:{uri.Port}",
            PairingCode = Base32Crockford.Normalize(code),
            Pin = q["pin"],
            DeviceId = dev, KeyVersion = kv, Key = key, DeviceName = q["dn"],
        };
    }

    public override string ToString()
    {
        var parts = new List<string>();
        if (PairingCode is not null) parts.Add("c=" + PairingCode);
        if (DeviceId is not null) parts.Add("dev=" + DeviceId.Value.ToString("D"));
        if (KeyVersion is not null) parts.Add("kv=" + KeyVersion.Value);
        if (Key is not null) parts.Add("k=" + DeviceKey.FormatCompact(Key));
        if (DeviceName is not null) parts.Add("dn=" + Uri.EscapeDataString(DeviceName));
        if (Pin is not null) parts.Add("pin=" + Pin);
        var query = string.Join("&", parts);
        return PairingCode is null ? $"{Scheme}://key?{query}" : $"{Scheme}://{Host}/pair?{query}";
    }
}
