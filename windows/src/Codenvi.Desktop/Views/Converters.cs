using System.Globalization;
using System.Windows;
using System.Windows.Data;
using Codenvi.Desktop.Services;

namespace Codenvi.Desktop.Views;

public sealed class InverseBoolToVisibilityConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture) =>
        value is true ? Visibility.Collapsed : Visibility.Visible;

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
        value is Visibility.Collapsed;
}

/// <summary>null → Collapsed (or Visible with ConverterParameter=invert).</summary>
public sealed class NullToVisibilityConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var visible = value is not null;
        if (parameter as string == "invert") visible = !visible;
        return visible ? Visibility.Visible : Visibility.Collapsed;
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) => throw new NotSupportedException();
}

/// <summary>Status chip colors: running = orange, queued/waiting = amber, error = red, idle = muted (§0.7).</summary>
public sealed class StatusBrushConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        var key = value switch
        {
            ProjectActivity.Running => "Primary",
            ProjectActivity.Queued or ProjectActivity.WaitingLogin => "Warning",
            ProjectActivity.Error => "Error",
            true => "Success",
            false => "Error",
            _ => "OnSurfaceMuted",
        };
        return Application.Current.Resources[key];
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) => throw new NotSupportedException();
}
