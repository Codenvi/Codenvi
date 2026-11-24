using System.ComponentModel;
using System.Windows;
using Codenvi.Desktop.Native;
using Codenvi.Desktop.Services;
using Codenvi.Desktop.ViewModels;
using Microsoft.Extensions.DependencyInjection;

namespace Codenvi.Desktop.Views;

public partial class MainWindow : Window
{
    private readonly AppState _state;

    public MainWindow()
    {
        InitializeComponent();
        var vm = App.Services.GetRequiredService<MainViewModel>();
        vm.Owner = this;
        DataContext = vm;
        _state = App.Services.GetRequiredService<AppState>();
        SourceInitialized += (_, _) => NativeMethods.UseDarkTitleBar(this);
    }

    /// <summary>Minimize to tray on close (setting), so jobs keep running.</summary>
    protected override void OnClosing(CancelEventArgs e)
    {
        if (_state.Settings.MinimizeToTray && !_quitting)
        {
            e.Cancel = true;
            Hide();
            return;
        }
        base.OnClosing(e);
    }

    private bool _quitting;

    protected override void OnClosed(EventArgs e)
    {
        base.OnClosed(e);
        if (!_state.Settings.MinimizeToTray && !_quitting) ((App)Application.Current).Quit();
    }

    public void CloseForReset()
    {
        _quitting = true;
        Close();
    }
}
