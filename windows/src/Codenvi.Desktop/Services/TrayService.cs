using System.Drawing;
using System.Windows;
using Forms = System.Windows.Forms;

namespace Codenvi.Desktop.Services;

/// <summary>
/// WinForms NotifyIcon showing the working state (docs/design.md §8.2): normal icon when idle, orange-accent
/// icon with "Codenvi - n tasks running" while busy, red badge on error. Menu: Open, Pause all, Quit.
/// </summary>
public sealed class TrayService : IDisposable
{
    private readonly AppState _state;
    private readonly Forms.NotifyIcon _icon;
    private readonly Icon _idle;
    private readonly Icon _busy;
    private readonly Icon _error;
    private readonly Forms.ToolStripMenuItem _pauseItem;

    public event Action? OpenRequested;
    public event Action? QuitRequested;

    public TrayService(AppState state)
    {
        _state = state;
        _idle = LoadIcon("codenvi.ico");
        _busy = LoadIcon("codenvi-busy.ico");
        _error = WithBadge(_idle);

        _pauseItem = new Forms.ToolStripMenuItem("Pause all") { CheckOnClick = true, Checked = state.Settings.PauseAll };
        _pauseItem.CheckedChanged += (_, _) =>
        {
            var s = _state.Settings;
            s.PauseAll = _pauseItem.Checked;
            _state.SaveSettings(s);
        };
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add("Open", null, (_, _) => OpenRequested?.Invoke());
        menu.Items.Add(_pauseItem);
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add("Quit", null, (_, _) => QuitRequested?.Invoke());

        _icon = new Forms.NotifyIcon { Icon = _idle, Text = "Codenvi", Visible = true, ContextMenuStrip = menu };
        _icon.DoubleClick += (_, _) => OpenRequested?.Invoke();
        _state.Changed += Refresh;
        Refresh();
    }

    private void Refresh()
    {
        var app = System.Windows.Application.Current;
        if (app is null) return;
        app.Dispatcher.BeginInvoke(() =>
        {
            var running = _state.RunningCount;
            var error = !_state.ServerConnected || _state.Revoked || _state.Claude.State is Claude.ClaudeState.NotFound
                or Claude.ClaudeState.NotSignedIn or Claude.ClaudeState.TooOld;
            _icon.Icon = running > 0 ? _busy : error ? _error : _idle;
            var text = running > 0 ? $"Codenvi - {running} task{(running == 1 ? "" : "s")} running"
                : _state.Revoked ? "Codenvi - disconnected"
                : !_state.ServerConnected ? "Codenvi - offline"
                : error ? "Codenvi - Claude Code needs attention"
                : _state.Settings.PauseAll ? "Codenvi - paused" : "Codenvi - idle";
            _icon.Text = text.Length > 63 ? text[..63] : text;
            _pauseItem.Checked = _state.Settings.PauseAll;
        });
    }

    public void ShowBalloon(string title, string text) => _icon.ShowBalloonTip(5000, title, text, Forms.ToolTipIcon.Info);

    private static Icon LoadIcon(string name)
    {
        var info = System.Windows.Application.GetResourceStream(new Uri($"pack://application:,,,/Assets/{name}"));
        return info is null ? SystemIcons.Application : new Icon(info.Stream);
    }

    private static Icon WithBadge(Icon baseIcon)
    {
        using var bmp = baseIcon.ToBitmap();
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            var d = bmp.Width / 2;
            using var brush = new SolidBrush(Color.FromArgb(0xEF, 0x53, 0x50));
            g.FillEllipse(brush, bmp.Width - d, bmp.Height - d, d - 1, d - 1);
        }
        return Icon.FromHandle(bmp.GetHicon());
    }

    public void Dispose()
    {
        _state.Changed -= Refresh;
        _icon.Visible = false;
        _icon.Dispose();
    }
}
