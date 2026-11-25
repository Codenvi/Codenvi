using System.Collections.Specialized;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace Codenvi.Desktop.Views;

/// <summary>Attached behaviour: a ListBox with AutoScroll.Enabled="True" keeps its newest line in view.</summary>
public static class AutoScroll
{
    public static readonly DependencyProperty EnabledProperty =
        DependencyProperty.RegisterAttached("Enabled", typeof(bool), typeof(AutoScroll), new PropertyMetadata(false, OnChanged));

    public static bool GetEnabled(DependencyObject d) => (bool)d.GetValue(EnabledProperty);
    public static void SetEnabled(DependencyObject d, bool value) => d.SetValue(EnabledProperty, value);

    private static void OnChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not ListBox list) return;
        // ListBox.Items raises CollectionChanged for every ItemsSource it shows, including a replaced one.
        var items = (INotifyCollectionChanged)list.Items;
        NotifyCollectionChangedEventHandler onChange = (_, args) =>
        {
            if (args.Action is NotifyCollectionChangedAction.Add or NotifyCollectionChangedAction.Reset) ScrollToEnd(list);
        };
        RoutedEventHandler onLoaded = (_, _) => ScrollToEnd(list);
        DependencyPropertyChangedEventHandler onVisible = (_, args) => { if (args.NewValue is true) ScrollToEnd(list); };
        if ((bool)e.NewValue)
        {
            items.CollectionChanged += onChange; list.Loaded += onLoaded; list.IsVisibleChanged += onVisible;
            list.Tag = (onChange, onLoaded, onVisible);
        }
        else if (list.Tag is ValueTuple<NotifyCollectionChangedEventHandler, RoutedEventHandler, DependencyPropertyChangedEventHandler> h)
        {
            items.CollectionChanged -= h.Item1; list.Loaded -= h.Item2; list.IsVisibleChanged -= h.Item3;
        }
    }

    private static void ScrollToEnd(ListBox list)
    {
        // After layout, so the new item has been measured.
        list.Dispatcher.BeginInvoke(() =>
        {
            if (list.Items.Count == 0) return;
            if (FindScrollViewer(list) is { } sv) sv.ScrollToEnd();
            else list.ScrollIntoView(list.Items[^1]);
        }, System.Windows.Threading.DispatcherPriority.Background);
    }

    private static ScrollViewer? FindScrollViewer(DependencyObject d)
    {
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(d); i++)
        {
            var child = VisualTreeHelper.GetChild(d, i);
            if (child is ScrollViewer sv) return sv;
            if (FindScrollViewer(child) is { } found) return found;
        }
        return null;
    }
}
