using System.Text.RegularExpressions;

namespace Codenvi.Contracts;

/// <summary>
/// The <c>X-Codenvi-Client</c> header: <c>android/1.4.0+42 (Android 14; Pixel 7)</c> or
/// <c>desktop/1.4.0+57 (Windows 11 26100)</c>.
/// </summary>
public sealed partial record ClientInfo(string Kind, string Version, int Build, string? Os, string? Model)
{
    public const string HeaderInstallId = "X-Codenvi-Install-Id";
    public const string HeaderClient = "X-Codenvi-Client";

    public override string ToString()
    {
        var detail = Model is null ? Os : $"{Os}; {Model}";
        return detail is null ? $"{Kind}/{Version}+{Build}" : $"{Kind}/{Version}+{Build} ({detail})";
    }

    public static bool TryParse(string? header, out ClientInfo? info)
    {
        info = null;
        if (string.IsNullOrWhiteSpace(header)) return false;
        var m = Pattern().Match(header.Trim());
        if (!m.Success) return false;
        string? os = null, model = null;
        if (m.Groups["detail"].Success)
        {
            var parts = m.Groups["detail"].Value.Split(';', 2, StringSplitOptions.TrimEntries);
            os = parts[0];
            if (parts.Length > 1) model = parts[1];
        }
        info = new ClientInfo(m.Groups["kind"].Value, m.Groups["ver"].Value, int.Parse(m.Groups["build"].Value), os, model);
        return true;
    }

    [GeneratedRegex(@"^(?<kind>desktop|android)/(?<ver>[0-9A-Za-z.\-]+)\+(?<build>\d{1,9})(?:\s*\((?<detail>[^)]*)\))?$")]
    private static partial Regex Pattern();
}
