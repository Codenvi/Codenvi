namespace Codenvi.Desktop.Core;

/// <summary>Cleans up the host name typed into "Connect to server" (docs/self-hosting.md).</summary>
public static class ServerAddress
{
    /// <summary>
    /// Accepts <c>codenvi.example.com</c>, <c>codenvi.example.com:8443</c>, <c>https://codenvi.example.com/</c> and
    /// returns <c>host[:port]</c>, or null when it is not a usable host name.
    /// </summary>
    public static string? Normalize(string? input)
    {
        var s = (input ?? "").Trim();
        if (s.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) s = s[8..];
        else if (s.StartsWith("http://", StringComparison.OrdinalIgnoreCase)) return null; // the apps only speak HTTPS
        s = s.TrimEnd('/');
        if (s.Length == 0 || s.Contains('/') || s.Contains(' ') || s.Contains('@') || s.Contains('?') || s.Contains('#')) return null;
        if (!Uri.TryCreate("https://" + s, UriKind.Absolute, out var uri) || string.IsNullOrEmpty(uri.Host)) return null;
        if (!uri.Host.Contains('.') && !uri.Host.Equals("localhost", StringComparison.OrdinalIgnoreCase)) return null;
        return uri.IsDefaultPort ? uri.Host.ToLowerInvariant() : $"{uri.Host.ToLowerInvariant()}:{uri.Port}";
    }
}
