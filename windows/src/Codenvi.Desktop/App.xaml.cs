using System.Windows;
using Codenvi.Desktop.Claude;
using Codenvi.Desktop.Core;
using Codenvi.Desktop.Git;
using Codenvi.Desktop.Jobs;
using Codenvi.Desktop.Native;
using Codenvi.Desktop.Net;
using Codenvi.Desktop.Services;
using Codenvi.Desktop.ViewModels;
using Codenvi.Desktop.Views;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Serilog;

namespace Codenvi.Desktop;

public partial class App : Application
{
    private const string MutexName = "Local\\Codenvi.SingleInstance";
    private Mutex? _mutex;
    private IHost? _host;
    private TrayService? _tray;

    public static IServiceProvider Services => ((App)Current)._host!.Services;

    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        // Single instance: bring the existing window forward (Project.md §8.2).
        _mutex = new Mutex(true, MutexName, out var createdNew);
        if (!createdNew)
        {
            NativeMethods.BringToFront(NativeMethods.FindWindow(null, "Codenvi"));
            Shutdown();
            return;
        }

        AppPaths.Ensure();
        Log.Logger = new LoggerConfiguration()
            .MinimumLevel.Information()
            .WriteTo.File(Path.Combine(AppPaths.Logs, "codenvi-.log"), rollingInterval: RollingInterval.Day, retainedFileCountLimit: 14)
            .CreateLogger();
        DispatcherUnhandledException += (_, ex) =>
        {
            Log.Error(ex.Exception, "Unhandled UI exception");
            MessageBox.Show(ex.Exception.Message, "Codenvi", MessageBoxButton.OK, MessageBoxImage.Error);
            ex.Handled = true;
        };

        _host = Host.CreateDefaultBuilder()
            .UseSerilog()
            .ConfigureServices(s =>
            {
                s.AddSingleton<SecretStore>();
                s.AddSingleton<LocalDb>();
                s.AddSingleton<AppState>();
                s.AddSingleton<CryptoService>();
                s.AddSingleton<ApiClient>();
                s.AddSingleton<WsClient>();
                s.AddHostedService(sp => sp.GetRequiredService<WsClient>());
                s.AddSingleton<ClaudeCli>();
                s.AddSingleton<GitService>();
                s.AddSingleton<ProjectService>();
                s.AddSingleton<JobScheduler>();
                s.AddHostedService(sp => sp.GetRequiredService<JobScheduler>());
                s.AddSingleton<ControlHandler>();
                s.AddSingleton<SyncService>();
                s.AddHostedService(sp => sp.GetRequiredService<SyncService>());
                s.AddSingleton<MainViewModel>();
            })
            .Build();
        await _host.StartAsync();

        var state = Services.GetRequiredService<AppState>();
        _tray = new TrayService(state);
        _tray.OpenRequested += ShowMain;
        _tray.QuitRequested += Quit;
        state.Notify += msg => Dispatcher.BeginInvoke(() => _tray?.ShowBalloon("Codenvi", msg));

        if (!state.Settings.DisclaimerAccepted || !Services.GetRequiredService<SecretStore>().IsPaired)
            new ConnectWindow().Show();
        else if (!Services.GetRequiredService<SecretStore>().HasDevice)
            new DeviceSetupWindow().Show();
        else
            ShowMain();
    }

    public void ShowMain()
    {
        if (MainWindow is Views.MainWindow existing)
        {
            existing.Show();
            if (existing.WindowState == WindowState.Minimized) existing.WindowState = WindowState.Normal;
            existing.Activate();
            return;
        }
        var w = new Views.MainWindow();
        MainWindow = w;
        w.Show();
    }

    public async void Quit()
    {
        _tray?.Dispose();
        _tray = null;
        if (_host is not null)
        {
            try { await _host.StopAsync(TimeSpan.FromSeconds(5)); } catch { }
            _host.Dispose();
        }
        Log.CloseAndFlush();
        _mutex?.ReleaseMutex();
        Shutdown();
    }

    /// <summary>After "Disconnect this PC" or a revoke: back to the first-run screen.</summary>
    public void ResetToConnect()
    {
        foreach (var w in Windows.Cast<Window>().ToList())
        {
            if (w is Views.MainWindow m) m.CloseForReset();
            else if (w is not ConnectWindow) w.Close();
        }
        MainWindow = null;
        new ConnectWindow().Show();
    }
}
