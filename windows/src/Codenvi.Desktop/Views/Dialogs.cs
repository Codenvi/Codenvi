using System.Windows;
using System.Windows.Controls;
using Codenvi.Desktop.Native;
using Codenvi.Desktop.Services;

namespace Codenvi.Desktop.Views;

/// <summary>Small code-only dialogs styled with the dark theme.</summary>
public static class Dialogs
{
    private static Window Make(Window? owner, string title, double width)
    {
        var w = new Window
        {
            Title = title,
            Width = width,
            SizeToContent = SizeToContent.Height,
            WindowStartupLocation = owner is null ? WindowStartupLocation.CenterScreen : WindowStartupLocation.CenterOwner,
            ResizeMode = ResizeMode.NoResize,
            Owner = owner?.IsVisible == true ? owner : null,
            Style = (Style)Application.Current.Resources["CodenviWindow"],
            ShowInTaskbar = owner is null,
        };
        w.SourceInitialized += (_, _) => NativeMethods.UseDarkTitleBar(w);
        return w;
    }

    private static StackPanel Buttons(params Button[] buttons)
    {
        var p = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 16, 0, 0) };
        foreach (var b in buttons) p.Children.Add(b);
        return p;
    }

    private static Button Btn(string text, bool primary = false)
    {
        var b = new Button { Content = text, MinWidth = 90 };
        if (primary) b.Style = (Style)Application.Current.Resources["PrimaryButton"];
        return b;
    }

    public static string? Input(Window? owner, string title, string label, string initial = "")
    {
        var w = Make(owner, title, 440);
        var box = new TextBox { Text = initial, Margin = new Thickness(0, 8, 0, 0) };
        var ok = Btn("OK", true);
        ok.IsDefault = true;
        var cancel = Btn("Cancel");
        cancel.IsCancel = true;
        string? result = null;
        ok.Click += (_, _) => { result = box.Text.Trim(); w.DialogResult = true; };
        w.Content = new StackPanel { Margin = new Thickness(20), Children = { new TextBlock { Text = label }, box, Buttons(ok, cancel) } };
        w.Loaded += (_, _) => { box.Focus(); box.SelectAll(); };
        return w.ShowDialog() == true && !string.IsNullOrWhiteSpace(result) ? result : null;
    }

    public static int Choose(Window? owner, string title, string message, string[] options)
    {
        var w = Make(owner, title, 520);
        var chosen = -1;
        var buttons = options.Select((o, i) =>
        {
            var b = Btn(o, i == 0);
            b.Click += (_, _) => { chosen = i; w.DialogResult = true; };
            return b;
        }).ToArray();
        w.Content = new StackPanel { Margin = new Thickness(20), Children = { new TextBlock { Text = message }, Buttons(buttons) } };
        w.ShowDialog();
        return chosen == options.Length - 1 && options[^1] == "Cancel" ? -1 : chosen;
    }

    public static bool Confirm(Window? owner, string title, string message, string yes = "Yes", string no = "Cancel") =>
        Choose(owner, title, message, [yes, no]) == 0;

    public static void Info(Window? owner, string title, string message) => Choose(owner, title, message, ["OK"]);

    public static void Error(Window? owner, string message) => Info(owner, "Codenvi", message);

    /// <summary>Shows a secret string (connection string / key) with Copy.</summary>
    public static void ShowSecret(Window? owner, string title, string explanation, string secret)
    {
        var w = Make(owner, title, 620);
        var box = new TextBox
        {
            Text = secret, IsReadOnly = true, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 10, 0, 0),
            Style = (Style)Application.Current.Resources["MonoBox"], FontSize = 14,
        };
        var copy = Btn("Copy", true);
        copy.Click += (_, _) => { Clipboard.SetText(secret); copy.Content = "Copied"; };
        var close = Btn("Close");
        close.IsCancel = true;
        close.Click += (_, _) => w.Close();
        w.Content = new StackPanel { Margin = new Thickness(20), Children = { new TextBlock { Text = explanation }, box, Buttons(copy, close) } };
        w.Closed += (_, _) => box.Text = "";
        w.ShowDialog();
    }
}

/// <summary>IUserPrompts for ProjectService, backed by the dialogs above.</summary>
public sealed class WindowPrompts(Window? owner) : IUserPrompts
{
    public bool Confirm(string title, string message) => Dialogs.Confirm(owner, title, message);
    public int Choose(string title, string message, params string[] options) => Dialogs.Choose(owner, title, message, options);
    public void Info(string title, string message) => Dialogs.Info(owner, title, message);
}

public static class DisclaimerText
{
    public const string Text =
        "Codenvi is an independent open-source project. It is not affiliated with, endorsed by, or sponsored by Anthropic, PBC. " +
        "\"Claude\" and \"Claude Code\" are trademarks of Anthropic, PBC.\n\n" +
        "Codenvi runs the official Claude Code CLI that you install and sign in to yourself on each PC. Codenvi never sees or stores " +
        "your Claude credentials. Your use of Claude Code is governed by Anthropic's terms, and you are responsible for complying with them.\n\n" +
        "Codenvi is intended for personal use on your own devices and your own server. It lets an AI agent change files and run commands " +
        "on your PCs. Use it at your own risk and review its changes before merging them.\n\n" +
        "Codenvi is provided \"as is\", without warranty of any kind, under the MIT License.";
}
