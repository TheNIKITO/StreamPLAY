using System.Diagnostics;
using System.Windows;
using LibVLCSharp.Shared;
using Microsoft.Win32;

namespace StreamPLAY;

public partial class App : Application
{
    public static LibVLC VLC { get; private set; }
    public static string VlcDir { get; private set; }
    static Task _vlcReady;
    static bool _errorOpen;
    static DateTime _lastError;

    public static Task VlcReady => _vlcReady;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        DispatcherUnhandledException += (_, a) =>
        {
            Log(a.Exception);
            a.Handled = true;
            if (_errorOpen || (DateTime.Now - _lastError).TotalSeconds < 15) return;
            _errorOpen = true;
            MessageBox.Show("Ocorreu um erro inesperado:\n\n" + a.Exception.Message, "StreamPLAY", MessageBoxButton.OK, MessageBoxImage.Warning);
            _errorOpen = false;
            _lastError = DateTime.Now;
        };
        AppDomain.CurrentDomain.UnhandledException += (_, a) => Log(a.ExceptionObject as Exception);

        Store.Load();
        if (e.Args.Length > 0 && File.Exists(e.Args[0])) Store.D.Source = Path.GetFullPath(e.Args[0]);

        VlcDir = FindVlc();
        if (VlcDir == null)
        {
            var r = MessageBox.Show(
                "O StreamPLAY usa o motor do VLC para reproduzir os vídeos, mas ele não foi encontrado neste computador.\n\n" +
                "Instale o VLC (64 bits) e abra o StreamPLAY de novo.\n\nAbrir o site do VLC agora?",
                "StreamPLAY", MessageBoxButton.YesNo, MessageBoxImage.Information);
            if (r == MessageBoxResult.Yes) Process.Start(new ProcessStartInfo("https://www.videolan.org/vlc/") { UseShellExecute = true });
            Shutdown();
            return;
        }

        _vlcReady = Task.Run(() =>
        {
            Core.Initialize(VlcDir);
            VLC = new LibVLC("--no-video-title-show", "--quiet", "--no-snapshot-preview", "--no-stats",
                             "--freetype-rel-fontsize=18", "--freetype-outline-thickness=4");
        });

        new MainWindow().Show();
    }

    static string FindVlc()
    {
        var c = new List<string>
        {
            Path.Combine(AppContext.BaseDirectory, "vlc"),
            Path.Combine(AppContext.BaseDirectory, "libvlc", "win-x64"),
        };
        try
        {
            using var k = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64).OpenSubKey(@"SOFTWARE\VideoLAN\VLC");
            if (k?.GetValue("InstallDir") is string dir) c.Add(dir);
        }
        catch { }
        c.Add(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "VideoLAN", "VLC"));
        return c.FirstOrDefault(d => d != null && File.Exists(Path.Combine(d, "libvlc.dll")) && Directory.Exists(Path.Combine(d, "plugins")));
    }

    public static void Log(Exception ex)
    {
        if (ex == null) return;
        try
        {
            Directory.CreateDirectory(Store.Dir);
            File.AppendAllText(Path.Combine(Store.Dir, "erros.log"), $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] {ex}\n\n");
        }
        catch { }
    }
}
