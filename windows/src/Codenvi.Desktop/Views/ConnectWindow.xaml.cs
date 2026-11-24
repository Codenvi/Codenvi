using System.Windows;
using Codenvi.Contracts;
using Codenvi.Contracts.Api;
using Codenvi.Desktop.Core;
using Codenvi.Desktop.Native;
using Codenvi.Desktop.Net;
using Codenvi.Desktop.Services;
using Microsoft.Extensions.DependencyInjection;

namespace Codenvi.Desktop.Views;

/// <summary>First run (Project.md §8.4 screen 1): disclaimer, then the server's host name and password.</summary>
public partial class ConnectWindow : Window
{
    private readonly AppState _state = App.Services.GetRequiredService<AppState>();
    private readonly SecretStore _secrets = App.Services.GetRequiredService<SecretStore>();
    private bool _done;

    public ConnectWindow()
    {
        InitializeComponent();
        SourceInitialized += (_, _) => NativeMethods.UseDarkTitleBar(this);
        DisclaimerTextBlock.Text = DisclaimerText.Text;
        NameBox.Text = Environment.MachineName;
        if (_state.Settings.DisclaimerAccepted) ShowConnect();
    }

    private void ShowConnect()
    {
        DisclaimerPanel.Visibility = Visibility.Collapsed;
        ConnectPanel.Visibility = Visibility.Visible;
        HostBox.Focus();
    }

    private void OnAccept(object sender, RoutedEventArgs e)
    {
        var s = _state.Settings;
        s.DisclaimerAccepted = true;
        _state.SaveSettings(s);
        ShowConnect();
    }

    private void OnQuit(object sender, RoutedEventArgs e) => ((App)Application.Current).Quit();

    private async void OnConnect(object sender, RoutedEventArgs e)
    {
        ErrorText.Visibility = Visibility.Collapsed;
        string host;
        string code;
        string? pin = null;
        var hostText = HostBox.Text.Trim();
        if (hostText.StartsWith("codenvi://", StringComparison.OrdinalIgnoreCase))
        {
            // A connection string (from "Pair another PC" or the VPS command) pasted into the host box still works.
            try
            {
                var cs = ConnectionString.Parse(hostText);
                if (cs.PairingCode is null || cs.Host is null) throw new FormatException("This is a key-only string, not a server connection.");
                (host, code, pin) = (cs.Host, cs.PairingCode, cs.Pin);
            }
            catch (Exception ex) when (ex is FormatException or UriFormatException)
            {
                ShowError("That doesn't look like a Codenvi connection string: " + ex.Message);
                return;
            }
        }
        else
        {
            var normalized = ServerAddress.Normalize(hostText);
            if (normalized is null) { ShowError("Enter the server's host name, for example codenvi.example.com."); return; }
            code = PasswordBox.Password.Trim();
            if (code.Length == 0) { ShowError("Enter the server's password."); return; }
            host = normalized;
        }
        var name = NameBox.Text.Trim();
        if (name.Length == 0) { ShowError("Enter a name for this PC."); return; }

        if (!Dialogs.Confirm(this, "Confirm server", $"Connect this PC to\n\n    {host}\n\nOnly continue if this is your own Codenvi server.", "Connect"))
            return;

        ConnectButton.IsEnabled = false;
        try
        {
            var installId = _secrets.Current.InstallId == Guid.Empty ? Guid.NewGuid() : _secrets.Current.InstallId;
            var tokens = await ApiClient.PairAsync(host, pin, new PairRequest(code, installId, "desktop", name,
                AppPaths.AppVersion, AppPaths.AppBuild, AppPaths.OsDescription, null));
            _secrets.Update(s =>
            {
                s.InstallId = installId;
                s.ServerHost = host;
                s.Pin = pin;
                s.RefreshToken = tokens.RefreshToken;
                s.DeviceName = name;
            });
            App.Services.GetRequiredService<ApiClient>().AcceptTokens(tokens);
            App.Services.GetRequiredService<WsClient>().Reconnect();
            _done = true;
            new DeviceSetupWindow().Show();
            Close();
        }
        catch (Exception ex) when (ex is ApiException or HttpRequestException or TaskCanceledException)
        {
            ShowError(ex is ApiException api && api.Status == System.Net.HttpStatusCode.Unauthorized
                ? "The host name or password is wrong (or linking was locked after too many wrong attempts: see docs/self-hosting.md)."
                : "Could not pair: " + ex.Message);
        }
        finally { ConnectButton.IsEnabled = true; }
    }

    private void ShowError(string message)
    {
        ErrorText.Text = message;
        ErrorText.Visibility = Visibility.Visible;
    }

    protected override void OnClosed(EventArgs e)
    {
        base.OnClosed(e);
        if (!_done && Application.Current.Windows.Count == 0) ((App)Application.Current).Quit();
    }
}
