using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using Microsoft.Extensions.DependencyInjection;
using Codenvi.Desktop.ViewModels;
using Codenvi.Desktop.Core;
using Codenvi.Desktop.Native;

namespace Codenvi.Desktop.Views;

/// <summary>About (docs/design.md §8.4 screen 5): version, license, repository, third-party notices, disclaimer.</summary>
public partial class AboutView : UserControl
{
    /// <summary>Set to the public repository URL.</summary>
    public const string RepositoryUrl = "https://github.com/Codenvi/Codenvi";

    /// <summary>The project website.</summary>
    public const string WebsiteUrl = "https://codenvi.github.io/Codenvi/";

    public AboutView()
    {
        InitializeComponent();
        VersionText.Text = $"Version {AppPaths.AppVersion} (build {AppPaths.AppBuild})";
        Disclaimer.Text = DisclaimerText.Text;
        RepoLink.Content = RepositoryUrl;
        WebLink.Content = WebsiteUrl;
    }

    private void OnRepo(object sender, RoutedEventArgs e) =>
        Process.Start(new ProcessStartInfo(RepositoryUrl) { UseShellExecute = true });

    private void OnWebsite(object sender, RoutedEventArgs e) =>
        Process.Start(new ProcessStartInfo(WebsiteUrl) { UseShellExecute = true });

    private void OnClose(object sender, RoutedEventArgs e) => App.Services.GetRequiredService<MainViewModel>().ShowProjects();
}
