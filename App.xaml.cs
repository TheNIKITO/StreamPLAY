using System.Diagnostics;
using System.Windows;
using System.Windows.Threading;
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

    public static Task VlcReady => _vlcReady ??= Task.Run(PrepararVlc);

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

        new MainWindow().Show();
        // O VLC vem dentro do app: na primeira vez é extraído em segundo plano, depois que a janela já apareceu
        // (enquanto a pessoa entra na conta), para não atrasar a abertura.
        var aposAbrir = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        aposAbrir.Tick += (_, _) => { aposAbrir.Stop(); _ = VlcReady; };
        aposAbrir.Start();
    }

    static void PrepararVlc()
    {
        VlcDir = VlcAoLado() ?? VlcEmbutido.Preparar() ?? VlcInstalado()
                 ?? throw new InvalidOperationException("não consegui preparar o motor de vídeo. Feche e abra o StreamPLAY de novo.");
        Core.Initialize(VlcDir);
        var args = new List<string>
        {
            "--no-video-title-show", "--quiet", "--no-snapshot-preview", "--no-stats",
            "--freetype-rel-fontsize=18", "--freetype-outline-thickness=4",
            // não mostra quadros quebrados (os "quadrados" no começo do vídeo): espera o primeiro quadro bom
            "--no-avcodec-corrupted",
        };
        // STREAMPLAY_VLC_LOG=arquivo grava o log detalhado do VLC (para investigar vídeo com problema)
        var log = Environment.GetEnvironmentVariable("STREAMPLAY_VLC_LOG");
        if (!string.IsNullOrEmpty(log)) { args.Remove("--quiet"); args.Add("--verbose=2"); }
        VLC = new LibVLC(args.ToArray());
        if (!string.IsNullOrEmpty(log))
        {
            var w = new StreamWriter(log, append: true) { AutoFlush = true };
            var trava = new object();
            VLC.Log += (_, e) => { lock (trava) w.WriteLine($"{DateTime.Now:HH:mm:ss.fff} [{e.Level}] {e.Module}: {e.Message}"); };
        }
    }

    public static bool VlcEhEmbutido => VlcDir != null && string.Equals(VlcDir, VlcEmbutido.Pasta, StringComparison.OrdinalIgnoreCase);

    static bool TemVlc(string d) => d != null && File.Exists(Path.Combine(d, "libvlc.dll")) && Directory.Exists(Path.Combine(d, "plugins"));

    // pasta "vlc" ao lado do exe: permite testar outra versão do motor sem recompilar
    static string VlcAoLado() => new[]
    {
        Path.Combine(AppContext.BaseDirectory, "vlc"),
        Path.Combine(AppContext.BaseDirectory, "libvlc", "win-x64"),
    }.FirstOrDefault(TemVlc);

    // último recurso, se a extração falhar: um VLC 64 bits instalado no PC
    static string VlcInstalado()
    {
        var c = new List<string>();
        try
        {
            using var k = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64).OpenSubKey(@"SOFTWARE\VideoLAN\VLC");
            if (k?.GetValue("InstallDir") is string dir) c.Add(dir);
        }
        catch { }
        c.Add(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "VideoLAN", "VLC"));
        return c.FirstOrDefault(TemVlc);
    }

    public static void Log(Exception ex)
    {
        if (ex != null) Log(ex.ToString());
    }

    public static void Log(string texto)
    {
        try
        {
            Directory.CreateDirectory(Store.Dir);
            File.AppendAllText(Path.Combine(Store.Dir, "erros.log"), $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] {texto}\n\n");
        }
        catch { }
    }
}
