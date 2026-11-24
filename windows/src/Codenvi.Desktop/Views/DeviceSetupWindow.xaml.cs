using System.Windows;
using Codenvi.Contracts.Crypto;
using Codenvi.Desktop.Claude;
using Codenvi.Desktop.Core;
using Codenvi.Desktop.Native;
using Codenvi.Desktop.Net;
using Codenvi.Desktop.ViewModels;
using Microsoft.Extensions.DependencyInjection;

namespace Codenvi.Desktop.Views;

/// <summary>Device setup (Project.md §8.4 screen 2): generate the key, then Connect a phone.</summary>
public partial class DeviceSetupWindow : Window
{
    private readonly SecretStore _secrets = App.Services.GetRequiredService<SecretStore>();
    private bool _done;

    public DeviceSetupWindow()
    {
        InitializeComponent();
        SourceInitialized += (_, _) => NativeMethods.UseDarkTitleBar(this);
        if (_secrets.HasDevice) MarkRegistered();
        Loaded += async (_, _) =>
        {
            var status = await App.Services.GetRequiredService<ClaudeCli>().CheckAsync();
            ClaudeStatusText.Text = status.State == ClaudeState.Ready
                ? $"✓ Claude Code {status.Version} is installed and signed in."
                : status.Banner;
        };
    }

    private void MarkRegistered()
    {
        GenerateButton.IsEnabled = false;
        KeyStatus.Text = $"✓ Registered as \"{_secrets.Current.DeviceName}\" (key version {_secrets.Current.CurrentKeyVersion}).";
        PhoneButton.IsEnabled = true;
        PhoneButton.Style = (Style)FindResource("PrimaryButton");
        ContinueButton.IsEnabled = true;
    }

    private async void OnGenerate(object sender, RoutedEventArgs e)
    {
        GenerateButton.IsEnabled = false;
        ErrorText.Visibility = Visibility.Collapsed;
        try
        {
            var key = DeviceKey.Generate();
            var deviceId = _secrets.Current.DeviceId ?? Guid.NewGuid();
            var name = _secrets.Current.DeviceName ?? Environment.MachineName;
            var api = App.Services.GetRequiredService<ApiClient>();
            await api.RegisterDeviceAsync(deviceId, name, E2E.KeyCheck(key));
            _secrets.Update(s =>
            {
                s.DeviceId = deviceId;
                s.DeviceName = name;
                s.Keys[1] = key;
                s.CurrentKeyVersion = 1;
            });
            App.Services.GetRequiredService<WsClient>().Reconnect(); // hello now carries the device id
            MarkRegistered();
        }
        catch (Exception ex) when (ex is ApiException or HttpRequestException)
        {
            ErrorText.Text = "Could not register this PC: " + ex.Message;
            ErrorText.Visibility = Visibility.Visible;
            GenerateButton.IsEnabled = true;
        }
    }

    private async void OnPhone(object sender, RoutedEventArgs e)
    {
        var vm = App.Services.GetRequiredService<MainViewModel>();
        vm.Owner = this;
        await vm.ConnectPhoneCommand.ExecuteAsync(null);
    }

    private void OnContinue(object sender, RoutedEventArgs e)
    {
        _done = true;
        ((App)Application.Current).ShowMain();
        Close();
    }

    protected override void OnClosed(EventArgs e)
    {
        base.OnClosed(e);
        if (!_done && Application.Current.Windows.Count == 0) ((App)Application.Current).Quit();
    }
}
