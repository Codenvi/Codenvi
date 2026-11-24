using System.Net.Security;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using Codenvi.Contracts;
using Codenvi.Desktop.Core;

namespace Codenvi.Desktop.Net;

/// <summary>
/// Builds HTTP handlers. With a pin (self-signed setups, Project.md §4.1) the server's
/// SubjectPublicKeyInfo SHA-256 must match; otherwise normal TLS validation applies.
/// </summary>
public static class HttpFactory
{
    public static SocketsHttpHandler CreateHandler(string? pin)
    {
        var handler = new SocketsHttpHandler
        {
            PooledConnectionLifetime = TimeSpan.FromMinutes(5),
            ConnectTimeout = TimeSpan.FromSeconds(15),
        };
        if (!string.IsNullOrWhiteSpace(pin))
            handler.SslOptions = new SslClientAuthenticationOptions { RemoteCertificateValidationCallback = (_, cert, _, _) => MatchesPin(cert, pin) };
        return handler;
    }

    public static bool MatchesPin(X509Certificate? cert, string pin)
    {
        if (cert is null) return false;
        using var c2 = new X509Certificate2(cert);
        var spki = c2.PublicKey.ExportSubjectPublicKeyInfo();
        var hash = SHA256.HashData(spki);
        var expected = pin.Contains('+') || pin.Contains('/') ? Convert.FromBase64String(pin) : Base64UrlDecode(pin);
        return CryptographicOperations.FixedTimeEquals(hash, expected);
    }

    private static byte[] Base64UrlDecode(string s)
    {
        s = s.Replace('-', '+').Replace('_', '/');
        return Convert.FromBase64String(s.PadRight(s.Length + (4 - s.Length % 4) % 4, '='));
    }

    public static void AddCodenviHeaders(HttpRequestMessage req, Guid installId)
    {
        req.Headers.TryAddWithoutValidation(ClientInfo.HeaderInstallId, installId.ToString("D"));
        req.Headers.TryAddWithoutValidation(ClientInfo.HeaderClient, ClientHeader());
    }

    public static string ClientHeader() =>
        new ClientInfo("desktop", AppPaths.AppVersion, AppPaths.AppBuild, AppPaths.OsDescription, null).ToString();
}
