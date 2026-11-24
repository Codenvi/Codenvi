using System.Collections.ObjectModel;
using System.Windows;
using System.Windows.Threading;
using Codenvi.Contracts.Crypto;
using Codenvi.Desktop.Claude;
using Codenvi.Desktop.Core;
using Codenvi.Desktop.Git;
using Codenvi.Desktop.Jobs;
using Codenvi.Desktop.Native;
using Codenvi.Desktop.Net;
using Codenvi.Desktop.Services;
using Codenvi.Desktop.Views;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Codenvi.Desktop.ViewModels;

public sealed partial class MainViewModel : ObservableObject
{
    private readonly AppState _state;
    private readonly LocalDb _db;
    private readonly SecretStore _secrets;
    private readonly ProjectService _projects;
    private readonly JobScheduler _scheduler;
    private readonly ClaudeCli _claude;
    private readonly ApiClient _api;
    private readonly CryptoService _crypto;
    private readonly WsClient _ws;
    private readonly GitService _git;
    private readonly DispatcherTimer _timer;

    public Window? Owner { get; set; }
    public ObservableCollection<ProjectViewModel> Projects { get; } = new();

    [ObservableProperty] private string _serverHost = "";
    [ObservableProperty] private string _deviceName = "";
    [ObservableProperty] private bool _serverConnected;
    [ObservableProperty] private string _connectionText = "Connecting…";
    [ObservableProperty] private string _claudeText = "Claude Code: checking…";
    [ObservableProperty] private bool _claudeReady;
    [ObservableProperty] private string _claudeBanner = "";
    [ObservableProperty] private bool _showClaudeBanner;
    [ObservableProperty] private string _revokedBanner = "";
    [ObservableProperty] private bool _showRevokedBanner;
    [ObservableProperty] private ProjectViewModel? _selected;

    public MainViewModel(AppState state, LocalDb db, SecretStore secrets, ProjectService projects, JobScheduler scheduler,
        ClaudeCli claude, ApiClient api, CryptoService crypto, WsClient ws, GitService git)
    {
        _state = state; _db = db; _secrets = secrets; _projects = projects; _scheduler = scheduler;
        _claude = claude; _api = api; _crypto = crypto; _ws = ws; _git = git;

        state.Changed += () => Ui(RefreshHeader);
        state.ProjectsListChanged += () => Ui(Reload);
        state.ProjectChanged += id => Ui(() => Projects.FirstOrDefault(p => p.Id == id)?.RefreshStatus());
        state.ActivityLine += (id, line) => Ui(() => Projects.FirstOrDefault(p => p.Id == id)?.AddActivity(line));

        _timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _timer.Tick += (_, _) => { foreach (var p in Projects) p.RefreshElapsed(); };
        _timer.Start();

        RefreshHeader();
        Reload();
    }

    private static void Ui(Action a)
    {
        var d = Application.Current?.Dispatcher;
        if (d is null) return;
        if (d.CheckAccess()) a(); else d.BeginInvoke(a);
    }

    private void RefreshHeader()
    {
        ServerHost = _secrets.Current.ServerHost ?? "";
        DeviceName = _secrets.Current.DeviceName ?? Environment.MachineName;
        ServerConnected = _state.ServerConnected;
        ConnectionText = _state.Revoked ? "Disconnected" : _state.ServerConnected ? "Connected" : "Offline - reconnecting";
        var c = _state.Claude;
        ClaudeReady = c.State == ClaudeState.Ready;
        ClaudeText = c.State switch
        {
            ClaudeState.Ready => $"Claude Code {c.Version} · signed in",
            ClaudeState.NotSignedIn => $"Claude Code {c.Version} · not signed in",
            ClaudeState.TooOld => $"Claude Code {c.Version} · too old",
            ClaudeState.NotFound => "Claude Code CLI not found",
            _ => "Claude Code: checking…",
        };
        ClaudeBanner = c.Banner;
        ShowClaudeBanner = c.State is ClaudeState.NotFound or ClaudeState.NotSignedIn or ClaudeState.TooOld;
        ShowRevokedBanner = _state.Revoked || _state.UpgradeRequired;
        RevokedBanner = _state.UpgradeRequired
            ? "Update Codenvi - the server no longer accepts this version."
            : "This app was disconnected from the server (revoked). Use Settings → Disconnect this PC, then pair again.";
    }

    public void Reload()
    {
        var selectedId = Selected?.Id;
        var locals = _db.Projects();
        foreach (var gone in Projects.Where(p => locals.All(l => l.Id != p.Id)).ToList()) Projects.Remove(gone);
        foreach (var l in locals)
        {
            var vm = Projects.FirstOrDefault(p => p.Id == l.Id);
            if (vm is null) Projects.Add(vm = new ProjectViewModel(l, this, _state, _db, _projects, _scheduler, _claude, _git));
            else vm.Update(l);
        }
        Selected = Projects.FirstOrDefault(p => p.Id == selectedId) ?? Selected ?? Projects.FirstOrDefault();
    }

    partial void OnSelectedChanged(ProjectViewModel? value) => value?.LoadDetails();

    // ---------------- Commands ----------------
    [RelayCommand]
    private async Task RegisterProjectAsync()
    {
        using var dlg = new System.Windows.Forms.FolderBrowserDialog
        {
            Description = "Pick the project folder (a git repository, or a folder Codenvi should initialize)",
            UseDescriptionForTitle = true,
            ShowNewFolderButton = false,
        };
        if (dlg.ShowDialog() != System.Windows.Forms.DialogResult.OK) return;
        var name = Dialogs.Input(Owner, "Register project", "Project name:", Path.GetFileName(dlg.SelectedPath.TrimEnd('\\')));
        if (name is null) return;
        await Guard(async () =>
        {
            var p = await _projects.RegisterAsync(dlg.SelectedPath, name, new WindowPrompts(Owner));
            if (p is not null) { Reload(); Selected = Projects.FirstOrDefault(x => x.Id == p.Id); }
        });
    }

    [RelayCommand]
    private async Task RenameProjectAsync()
    {
        if (Selected is null) return;
        var name = Dialogs.Input(Owner, "Rename project", "New name:", Selected.Name);
        if (name is null) return;
        await Guard(() => _projects.RenameAsync(Selected.Project, name));
    }

    [RelayCommand]
    private async Task ChangeFolderAsync()
    {
        if (Selected is null) return;
        using var dlg = new System.Windows.Forms.FolderBrowserDialog { Description = "New project folder", UseDescriptionForTitle = true, SelectedPath = Selected.Project.FolderPath };
        if (dlg.ShowDialog() != System.Windows.Forms.DialogResult.OK) return;
        await Guard(() => _projects.ChangeFolderAsync(Selected.Project, dlg.SelectedPath, new WindowPrompts(Owner)));
    }

    [RelayCommand]
    private async Task DeleteProjectAsync()
    {
        if (Selected is null) return;
        if (!Dialogs.Confirm(Owner, "Delete project",
                $"Remove \"{Selected.Name}\" from Codenvi and the server, including its sessions, chat history and commit records?\n\n" +
                "The folder, the branch and the commits on disk are NOT deleted.", "Delete")) return;
        await Guard(() => _projects.DeleteAsync(Selected.Project));
    }

    [RelayCommand]
    private async Task ShowKeyAsync()
    {
        var hwnd = Owner is null ? IntPtr.Zero : new System.Windows.Interop.WindowInteropHelper(Owner).Handle;
        if (!await UserVerification.VerifyAsync(hwnd, "Confirm it's you to show the Codenvi encryption key")) return;
        Dialogs.ShowSecret(Owner, "Encryption key",
            $"Encryption key for \"{DeviceName}\" (version {_crypto.KeyVersion}). Anyone with this key and access to your server can send prompts to this PC.\n\n" +
            "Key-only string for a phone that is already paired:\n" + _crypto.KeyOnlyString(),
            _crypto.DisplayKey());
    }

    [RelayCommand]
    private async Task ConnectPhoneAsync() => await Guard(async () =>
    {
        var code = await _api.CreatePairingCodeAsync("android");
        var host = string.IsNullOrWhiteSpace(code.Host) ? _secrets.Current.ServerHost! : code.Host;
        var s = _crypto.PhoneConnectionString(host, code.Code, _secrets.Current.Pin);
        Dialogs.ShowSecret(Owner, "Connect a phone",
            "Paste this into Codenvi on your phone (Connect screen). It pairs the phone and stores this PC's encryption key. " +
            "It works once and expires in 15 minutes. Treat it like a password.", s);
    });

    [RelayCommand]
    private async Task PairAnotherPcAsync() => await Guard(async () =>
    {
        var code = await _api.CreatePairingCodeAsync("desktop");
        var host = string.IsNullOrWhiteSpace(code.Host) ? _secrets.Current.ServerHost! : code.Host;
        var s = new Codenvi.Contracts.ConnectionString { Host = host, PairingCode = code.Code, Pin = _secrets.Current.Pin }.ToString();
        Dialogs.ShowSecret(Owner, "Pair another PC", "Paste this into Codenvi on the other PC. Single use, expires in 15 minutes.", s);
    });

    [RelayCommand]
    private async Task RetryClaudeAsync() => await Guard(_scheduler.RetryWaitingAsync);

    [RelayCommand]
    private async Task RegenerateKeyAsync()
    {
        if (!Dialogs.Confirm(Owner, "Regenerate key",
                "Create a new encryption key for this PC? Phones must receive the new key, and their pending prompts for this PC are cancelled. " +
                "Old messages stay readable here.", "Regenerate")) return;
        await Guard(async () =>
        {
            var key = DeviceKey.Generate();
            var version = _crypto.KeyVersion + 1;
            await _api.RotateKeyAsync(_crypto.DeviceId, version, E2E.KeyCheck(key));
            _secrets.Update(s => { s.Keys[version] = key; s.CurrentKeyVersion = version; });
            foreach (var p in _db.Projects())
            {
                await _projects.UploadProjectAsync(p);
                foreach (var s in _db.Sessions(p.Id)) await _projects.UploadSessionAsync(s);
            }
            await ConnectPhoneAsync();
        });
    }

    [RelayCommand]
    private void OpenSettings() => new SettingsWindow { Owner = Owner }.ShowDialog();

    [RelayCommand]
    private void OpenAbout() => new AboutWindow { Owner = Owner }.ShowDialog();

    public async Task Guard(Func<Task> action)
    {
        try { await action(); }
        catch (Exception e) when (e is ProjectException or Git.GitException or ApiException or IOException or InvalidOperationException or UnauthorizedAccessException or HttpRequestException)
        {
            Dialogs.Error(Owner, e.Message);
        }
    }
}
