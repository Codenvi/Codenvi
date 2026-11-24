using System.Diagnostics;
using System.Windows;
using Codenvi.Desktop.Core;
using Codenvi.Desktop.Native;

namespace Codenvi.Desktop.Views;

/// <summary>About (docs/design.md §8.4 screen 5): version, license, repository, third-party notices, disclaimer.</summary>
public partial class AboutWindow : Window
{
    /// <summary>Set to the public repository URL.</summary>
    public const string RepositoryUrl = "https://github.com/Codenvi/Codenvi";

    /// <summary>The project website.</summary>
    public const string WebsiteUrl = "https://codenvi.github.io/Codenvi/";

    public AboutWindow()
    {
        InitializeComponent();
        SourceInitialized += (_, _) => NativeMethods.UseDarkTitleBar(this);
        VersionText.Text = $"Version {AppPaths.AppVersion} (build {AppPaths.AppBuild})";
        Disclaimer.Text = DisclaimerText.Text;
        RepoLink.Content = RepositoryUrl;
        WebLink.Content = WebsiteUrl;
    }

    private void OnRepo(object sender, RoutedEventArgs e) =>
        Process.Start(new ProcessStartInfo(RepositoryUrl) { UseShellExecute = true });

    private void OnWebsite(object sender, RoutedEventArgs e) =>
        Process.Start(new ProcessStartInfo(WebsiteUrl) { UseShellExecute = true });

    private void OnClose(object sender, RoutedEventArgs e) => Close();
}
