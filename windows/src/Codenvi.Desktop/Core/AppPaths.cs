namespace Codenvi.Desktop.Core;

/// <summary>%LOCALAPPDATA%\Codenvi (docs/design.md §8.3).</summary>
public static class AppPaths
{
    public static string Root { get; set; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Codenvi");

    public static string Database => Path.Combine(Root, "codenvi.db");
    public static string Secrets => Path.Combine(Root, "secrets.bin");
    public static string Logs => Path.Combine(Root, "logs");
    public static string JobLogs => Path.Combine(Logs, "jobs");
    public static string Scripts => Path.Combine(Root, "scripts");

    public static void Ensure()
    {
        Directory.CreateDirectory(Root);
        Directory.CreateDirectory(Logs);
        Directory.CreateDirectory(JobLogs);
        Directory.CreateDirectory(Scripts);
    }

    public static string AppVersion =>
        typeof(AppPaths).Assembly.GetName().Version is { } v ? $"{v.Major}.{v.Minor}.{v.Build}" : "1.0.0";

    /// <summary>Build number = third version component (CI sets Version=1.0.&lt;run_number&gt;).</summary>
    public static int AppBuild => typeof(AppPaths).Assembly.GetName().Version?.Build ?? 0;

    public static string OsDescription =>
        $"Windows {(Environment.OSVersion.Version.Build >= 22000 ? "11" : "10")} {Environment.OSVersion.Version.Build}";
}
