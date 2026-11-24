using System.Windows;
using Codenvi.Contracts.Api;
using Codenvi.Desktop.Core;
using Codenvi.Desktop.Native;
using Codenvi.Desktop.Net;
using Codenvi.Desktop.Services;
using Codenvi.Desktop.ViewModels;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Win32;

namespace Codenvi.Desktop.Views;

public sealed record InstallRowView(InstallDto Dto)
{
    public string Name => Dto.IsSelf ? Dto.Name + " (this PC)" : Dto.Name;
    public string Kind => Dto.Kind;
    public string Version => $"{Dto.AppVersion}+{Dto.AppBuild}";
    public string LastSeen => DateTimeOffset.FromUnixTimeMilliseconds(Dto.LastSeenAtMs).ToLocalTime().ToString("g");
    public string LastIp => Dto.LastIp ?? "";
    public string Status => Dto.Revoked ? "revoked" : "active";
}

/// <summary>Settings (docs/design.md §8.4 screen 4).</summary>
public partial class SettingsWindow : Window
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private readonly AppState _state = App.Services.GetRequiredService<AppState>();
    private readonly ApiClient _api = App.Services.GetRequiredService<ApiClient>();
    private readonly SecretStore _secrets = App.Services.GetRequiredService<SecretStore>();
    private readonly MainViewModel _vm = App.Services.GetRequiredService<MainViewModel>();

    public SettingsWindow()
    {
        InitializeComponent();
        SourceInitialized += (_, _) => NativeMethods.UseDarkTitleBar(this);
        var s = _state.Settings;
        StartWithWindows.IsChecked = s.StartWithWindows;
        MinimizeToTray.IsChecked = s.MinimizeToTray;
        AutoSwitchBranch.IsChecked = s.AutoSwitchBranch;
        DeleteTranscripts.IsChecked = s.DeleteTranscriptsOnClear;
        MaxParallel.Text = s.MaxParallelProjects.ToString();
        GitName.Text = s.GitAuthorName;
        GitEmail.Text = s.GitAuthorEmail;
        DevBranch.Text = s.DefaultDevBranch;
        StaleHours.Text = s.StaleJobHours.ToString();
        Loaded += async (_, _) => await LoadInstallsAsync();
    }

    private async Task LoadInstallsAsync()
    {
        try { InstallsGrid.ItemsSource = (await _api.InstallsAsync()).Select(i => new InstallRowView(i)).ToList(); }
        catch (Exception e) when (e is ApiException or HttpRequestException) { Dialogs.Error(this, e.Message); }
    }

    private void OnSave(object sender, RoutedEventArgs e)
    {
        var s = _state.Settings;
        s.StartWithWindows = StartWithWindows.IsChecked == true;
        s.MinimizeToTray = MinimizeToTray.IsChecked == true;
        s.AutoSwitchBranch = AutoSwitchBranch.IsChecked == true;
        s.DeleteTranscriptsOnClear = DeleteTranscripts.IsChecked == true;
        s.MaxParallelProjects = int.TryParse(MaxParallel.Text, out var mp) ? Math.Clamp(mp, 1, 16) : 2;
        s.GitAuthorName = string.IsNullOrWhiteSpace(GitName.Text) ? "Codenvi" : GitName.Text.Trim();
        s.GitAuthorEmail = string.IsNullOrWhiteSpace(GitEmail.Text) ? "codenvi@local" : GitEmail.Text.Trim();
        s.DefaultDevBranch = string.IsNullOrWhiteSpace(DevBranch.Text) ? "codenvi-dev" : DevBranch.Text.Trim();
        s.StaleJobHours = int.TryParse(StaleHours.Text, out var sh) ? Math.Clamp(sh, 1, 24 * 30) : 24;
        _state.SaveSettings(s);
        ApplyStartWithWindows(s.StartWithWindows);
        App.Services.GetRequiredService<Jobs.JobScheduler>().Wake();
        Close();
    }

    /// <summary>HKCU\...\Run entry (docs/design.md §8.4).</summary>
    private static void ApplyStartWithWindows(bool on)
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKey, writable: true) ?? Registry.CurrentUser.CreateSubKey(RunKey);
        if (on) key.SetValue("Codenvi", $"\"{Environment.ProcessPath}\"");
        else key.DeleteValue("Codenvi", throwOnMissingValue: false);
    }

    private void OnClose(object sender, RoutedEventArgs e) => Close();

    private async void OnRefresh(object sender, RoutedEventArgs e) => await LoadInstallsAsync();

    private async void OnRevoke(object sender, RoutedEventArgs e)
    {
        if (InstallsGrid.SelectedItem is not InstallRowView row) return;
        if (row.Dto.IsSelf)
        {
            OnDisconnect(sender, e);
            return;
        }
        if (!Dialogs.Confirm(this, "Revoke", $"Revoke \"{row.Dto.Name}\"? It is disconnected immediately and must be paired again.", "Revoke")) return;
        try { await _api.RevokeInstallAsync(row.Dto.Id, self: false); await LoadInstallsAsync(); }
        catch (Exception ex) when (ex is ApiException or HttpRequestException) { Dialogs.Error(this, ex.Message); }
    }

    private async void OnRenameInstall(object sender, RoutedEventArgs e)
    {
        if (InstallsGrid.SelectedItem is not InstallRowView row) return;
        var name = Dialogs.Input(this, "Rename", "New name:", row.Dto.Name);
        if (name is null) return;
        try
        {
            await _api.RenameInstallAsync(row.Dto.Id, name);
            if (row.Dto.IsSelf && _secrets.Current.DeviceId is { } dev)
            {
                await _api.RenameDeviceAsync(dev, name);
                _secrets.Update(s => s.DeviceName = name);
            }
            await LoadInstallsAsync();
        }
        catch (Exception ex) when (ex is ApiException or HttpRequestException) { Dialogs.Error(this, ex.Message); }
    }

    private async void OnConnectPhone(object sender, RoutedEventArgs e) { _vm.Owner = this; await _vm.ConnectPhoneCommand.ExecuteAsync(null); _vm.Owner = Owner; }
    private async void OnPairPc(object sender, RoutedEventArgs e) { _vm.Owner = this; await _vm.PairAnotherPcCommand.ExecuteAsync(null); _vm.Owner = Owner; }
    private async void OnRegenerateKey(object sender, RoutedEventArgs e) { _vm.Owner = this; await _vm.RegenerateKeyCommand.ExecuteAsync(null); _vm.Owner = Owner; }

    private async void OnDisconnect(object sender, RoutedEventArgs e)
    {
        if (!Dialogs.Confirm(this, "Disconnect this PC",
                "Revoke this PC on the server, remove it and its project registrations, and delete its tokens and encryption keys from this PC?\n\nProject folders, branches and commits on disk stay as they are.",
                "Disconnect")) return;
        try
        {
            if (_secrets.Current.DeviceId is { } dev) await _api.DeleteDeviceAsync(dev);
            await _api.RevokeInstallAsync(_secrets.Current.InstallId, self: true);
        }
        catch (Exception ex) when (ex is ApiException or HttpRequestException) { /* already revoked or offline: still clear locally */ }
        App.Services.GetRequiredService<LocalDb>().ClearRegistrations();
        _secrets.Clear();
        _secrets.Update(s => s.InstallId = Guid.NewGuid());
        App.Services.GetRequiredService<WsClient>().Reconnect();
        Close();
        ((App)Application.Current).ResetToConnect();
    }
}
