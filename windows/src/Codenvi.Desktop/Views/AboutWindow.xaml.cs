using System.Diagnostics;
using System.Windows;
using Codenvi.Desktop.Core;
using Codenvi.Desktop.Native;

namespace Codenvi.Desktop.Views;

/// <summary>About (Project.md §8.4 screen 5): version, license, repository, third-party notices, disclaimer.</summary>
public partial class AboutWindow : Window
{
    /// <summary>Set to the public repository URL.</summary>
    public const string RepositoryUrl = "https://github.com/Irakli Gzirishvili/codenvi";

    public AboutWindow()
    {
        InitializeComponent();
        SourceInitialized += (_, _) => NativeMethods.UseDarkTitleBar(this);
        VersionText.Text = $"Version {AppPaths.AppVersion} (build {AppPaths.AppBuild})";
        Disclaimer.Text = DisclaimerText.Text;
        RepoLink.Content = RepositoryUrl;
    }

    private void OnRepo(object sender, RoutedEventArgs e) =>
        Process.Start(new ProcessStartInfo(RepositoryUrl) { UseShellExecute = true });

    private void OnClose(object sender, RoutedEventArgs e) => Close();
}
