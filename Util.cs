using System.Globalization;
using System.Net.Http;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;

namespace StreamPLAY;

/// <summary>Multiplica um número pelo parâmetro (usado para manter a proporção dos cards).</summary>
public class Ratio : IValueConverter
{
    public object Convert(object value, Type t, object p, CultureInfo c) =>
        value is double d && d > 0 ? d * double.Parse((string)p, CultureInfo.InvariantCulture) : double.NaN;
    public object ConvertBack(object value, Type t, object p, CultureInfo c) => throw new NotSupportedException();
}

public static class UI
{
    public static readonly DependencyProperty RadiusProperty =
        DependencyProperty.RegisterAttached("Radius", typeof(CornerRadius), typeof(UI), new PropertyMetadata(new CornerRadius(0)));
    public static void SetRadius(DependencyObject d, CornerRadius v) => d.SetValue(RadiusProperty, v);
    public static CornerRadius GetRadius(DependencyObject d) => (CornerRadius)d.GetValue(RadiusProperty);

    // desenho do ícone das abas do menu (estilo Nav)
    public static readonly DependencyProperty IconProperty =
        DependencyProperty.RegisterAttached("Icon", typeof(Geometry), typeof(UI), new PropertyMetadata(null));
    public static void SetIcon(DependencyObject d, Geometry v) => d.SetValue(IconProperty, v);
    public static Geometry GetIcon(DependencyObject d) => (Geometry)d.GetValue(IconProperty);

    public static string Time(long ms)
    {
        if (ms < 0) ms = 0;
        var t = TimeSpan.FromMilliseconds(ms);
        return t.TotalHours >= 1 ? $"{(int)t.TotalHours}:{t.Minutes:00}:{t.Seconds:00}" : $"{t.Minutes}:{t.Seconds:00}";
    }

    public static string Left(long ms)
    {
        var t = TimeSpan.FromMilliseconds(Math.Max(0, ms));
        return t.TotalHours >= 1 ? $"Faltam {(int)t.TotalHours}h {t.Minutes}min" : $"Faltam {Math.Max(1, t.Minutes)}min";
    }

    public static T Find<T>(DependencyObject root) where T : DependencyObject
    {
        if (root == null) return null;
        for (int i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
        {
            var c = VisualTreeHelper.GetChild(root, i);
            if (c is T t) return t;
            var r = Find<T>(c);
            if (r != null) return r;
        }
        return null;
    }

    public static T Up<T>(DependencyObject d) where T : DependencyObject
    {
        while (d != null && d is not T) d = d is Visual || d is System.Windows.Media.Media3D.Visual3D ? VisualTreeHelper.GetParent(d) : LogicalTreeHelper.GetParent(d);
        return d as T;
    }

    public static void Fade(UIElement el, double to, int ms = 220, Action done = null)
    {
        var a = new DoubleAnimation(to, TimeSpan.FromMilliseconds(ms)) { EasingFunction = new QuadraticEase() };
        if (done != null) a.Completed += (_, _) => done();
        el.BeginAnimation(UIElement.OpacityProperty, a);
    }

    public static System.Windows.Shapes.Path Icon(string geometry, bool stroke, double thickness = 2)
    {
        var p = new System.Windows.Shapes.Path { Data = (Geometry)Application.Current.FindResource(geometry), Width = 24, Height = 24 };
        if (stroke)
        {
            p.Stroke = Brushes.White; p.StrokeThickness = thickness;
            p.StrokeStartLineCap = p.StrokeEndLineCap = PenLineCap.Round; p.StrokeLineJoin = PenLineJoin.Round;
        }
        else p.Fill = Brushes.White;
        return p;
    }

    public static List<List<T>> Rows<T>(IEnumerable<T> items, int cols) => items.Chunk(Math.Max(1, cols)).Select(c => c.ToList()).ToList();
}

/// <summary>Rolagem suave com a roda do mouse (ListBox/ScrollViewer).</summary>
public static class Smooth
{
    public static readonly DependencyProperty OnProperty =
        DependencyProperty.RegisterAttached("On", typeof(bool), typeof(Smooth), new PropertyMetadata(false, (d, e) =>
        {
            if (d is UIElement el && (bool)e.NewValue) el.PreviewMouseWheel += Wheel;
        }));
    public static void SetOn(DependencyObject d, bool v) => d.SetValue(OnProperty, v);
    public static bool GetOn(DependencyObject d) => (bool)d.GetValue(OnProperty);

    static readonly DependencyProperty OffsetProperty =
        DependencyProperty.RegisterAttached("Offset", typeof(double), typeof(Smooth), new PropertyMetadata(0.0, (d, e) =>
            (d as ScrollViewer)?.ScrollToVerticalOffset((double)e.NewValue)));

    static readonly Dictionary<ScrollViewer, (double target, DateTime at)> State = new();

    static void Wheel(object sender, MouseWheelEventArgs e)
    {
        var sv = sender as ScrollViewer ?? UI.Find<ScrollViewer>((DependencyObject)sender);
        if (sv == null || sv.ScrollableHeight <= 0) return;
        e.Handled = true;
        Rolar(sv, e.Delta);
    }

    /// <summary>Um "giro" da roda do mouse (delta 120 = um dente; negativo desce).</summary>
    public static void Rolar(ScrollViewer sv, int delta)
    {
        double cur = sv.VerticalOffset;
        double baseOff = State.TryGetValue(sv, out var st) && (DateTime.Now - st.at).TotalMilliseconds < 280 ? st.target : cur;
        double target = Math.Clamp(baseOff - delta * 1.4, 0, sv.ScrollableHeight);
        State[sv] = (target, DateTime.Now);
        var a = new DoubleAnimation(cur, target, TimeSpan.FromMilliseconds(280)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } };
        sv.BeginAnimation(OffsetProperty, a);
    }

    public static void Reset(ScrollViewer sv)
    {
        if (sv == null) return;
        sv.BeginAnimation(OffsetProperty, null);
        State.Remove(sv);
        sv.ScrollToVerticalOffset(0);
    }
}

/// <summary>Carrega imagens (http, arquivo local, data:) em segundo plano com cache em memória e disco.</summary>
public static class Img
{
    public static readonly DependencyProperty SrcProperty =
        DependencyProperty.RegisterAttached("Src", typeof(string), typeof(Img), new PropertyMetadata(null, OnSrc));
    public static void SetSrc(DependencyObject d, string v) => d.SetValue(SrcProperty, v);
    public static string GetSrc(DependencyObject d) => (string)d.GetValue(SrcProperty);

    public static readonly DependencyProperty DecodeProperty =
        DependencyProperty.RegisterAttached("Decode", typeof(int), typeof(Img), new PropertyMetadata(320));
    public static void SetDecode(DependencyObject d, int v) => d.SetValue(DecodeProperty, v);
    public static int GetDecode(DependencyObject d) => (int)d.GetValue(DecodeProperty);

    static readonly string CacheDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "StreamPLAY", "imagens");
    static readonly HttpClient Http = CreateHttp();
    static readonly SemaphoreSlim Gate = new(6);
    static readonly Dictionary<string, BitmapSource> Mem = new();
    static readonly LinkedList<string> Lru = new();
    static readonly HashSet<string> Failed = new();
    static readonly Dictionary<string, Task<BitmapSource>> Pending = new();

    static HttpClient CreateHttp()
    {
        var h = new HttpClient(new SocketsHttpHandler { AutomaticDecompression = System.Net.DecompressionMethods.All }) { Timeout = TimeSpan.FromSeconds(20) };
        h.DefaultRequestHeaders.UserAgent.ParseAdd("Mozilla/5.0 (Windows NT 10.0; Win64; x64) StreamPLAY/1.0");
        return h;
    }

    static async void OnSrc(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        var el = d as FrameworkElement;
        if (el == null) return;
        var url = e.NewValue as string;
        Apply(el, null);
        if (string.IsNullOrWhiteSpace(url)) return;

        double scale = 1;
        try { scale = VisualTreeHelper.GetDpi(el).DpiScaleX; } catch { }
        int w = (int)(GetDecode(el) * Math.Max(1, scale));
        var key = url + "|" + w;

        BitmapSource bmp;
        lock (Mem)
        {
            if (Mem.TryGetValue(key, out bmp)) { Lru.Remove(key); Lru.AddFirst(key); }
            else if (Failed.Contains(url)) return;
        }
        if (bmp != null) { el.BeginAnimation(UIElement.OpacityProperty, null); el.Opacity = 1; Apply(el, bmp); return; }

        el.BeginAnimation(UIElement.OpacityProperty, null);
        el.Opacity = 0;
        try { bmp = await Get(url, w, key); } catch { bmp = null; }
        if (GetSrc(el) != url || bmp == null) return;
        Apply(el, bmp);
        el.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(260)));
    }

    static void Apply(FrameworkElement el, BitmapSource bmp)
    {
        switch (el)
        {
            case Image i: i.Source = bmp; break;
            case Border b:
                b.Background = bmp == null ? null : new ImageBrush(bmp) { Stretch = Stretch.UniformToFill, AlignmentY = AlignmentY.Top };
                break;
        }
    }

    static Task<BitmapSource> Get(string url, int w, string key)
    {
        lock (Pending)
        {
            if (Pending.TryGetValue(key, out var t)) return t;
            t = Task.Run(() => Fetch(url, w, key));
            Pending[key] = t;
            return t;
        }
    }

    static async Task<BitmapSource> Fetch(string url, int w, string key)
    {
        try
        {
            var data = await Read(url);
            if (data == null) { lock (Mem) Failed.Add(url); return null; }
            var bmp = Decode(data, w);
            if (bmp == null) { lock (Mem) Failed.Add(url); return null; }
            lock (Mem)
            {
                Mem[key] = bmp;
                Lru.AddFirst(key);
                while (Lru.Count > 500) { Mem.Remove(Lru.Last.Value); Lru.RemoveLast(); }
            }
            return bmp;
        }
        finally { lock (Pending) Pending.Remove(key); }
    }

    static async Task<byte[]> Read(string url)
    {
        try
        {
            if (url.StartsWith("data:", StringComparison.OrdinalIgnoreCase))
            {
                int comma = url.IndexOf(',');
                return comma > 0 ? Convert.FromBase64String(url[(comma + 1)..]) : null;
            }
            if (!M3U.IsUrl(url))
            {
                var path = url.StartsWith("file:", StringComparison.OrdinalIgnoreCase) ? new Uri(url).LocalPath : url;
                return File.Exists(path) ? await File.ReadAllBytesAsync(path) : null;
            }
            var file = Path.Combine(CacheDir, M3U.Hash(url));
            if (File.Exists(file)) return await File.ReadAllBytesAsync(file);
            await Gate.WaitAsync();
            try
            {
                var bytes = await Http.GetByteArrayAsync(url);
                Directory.CreateDirectory(CacheDir);
                await File.WriteAllBytesAsync(file, bytes);
                return bytes;
            }
            finally { Gate.Release(); }
        }
        catch { return null; }
    }

    static BitmapSource Decode(byte[] data, int w)
    {
        try
        {
            var bi = new BitmapImage();
            using var ms = new MemoryStream(data);
            bi.BeginInit();
            bi.CacheOption = BitmapCacheOption.OnLoad;
            bi.CreateOptions = BitmapCreateOptions.IgnoreColorProfile;
            if (w > 0) bi.DecodePixelWidth = w;
            bi.StreamSource = ms;
            bi.EndInit();
            bi.Freeze();
            return bi;
        }
        catch { return null; }
    }
}
