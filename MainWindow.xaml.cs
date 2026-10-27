using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Shell;
using System.Windows.Media.Animation;
using System.Windows.Threading;

namespace StreamPLAY;

public interface IPage
{
    void Search(string text);
    void Refresh();
}

public partial class MainWindow : Window
{
    public static MainWindow I { get; private set; }
    public Library Lib { get; private set; }

    BrowseView _movies, _series;
    ChannelsView _channels;
    string _tab;
    PlayerView _player;
    FileSystemWatcher _watch;
    readonly DispatcherTimer _reloadTimer = new() { Interval = TimeSpan.FromMilliseconds(700) };
    readonly DispatcherTimer _searchTimer = new() { Interval = TimeSpan.FromMilliseconds(260) };
    readonly DispatcherTimer _toastTimer = new() { Interval = TimeSpan.FromSeconds(3.5) };
    // STREAMPLAY_CHECK_SECONDS só existe para testes; o normal é conferir a cada 10 minutos
    static readonly TimeSpan RemoteEvery = int.TryParse(Environment.GetEnvironmentVariable("STREAMPLAY_CHECK_SECONDS"), out var sec) && sec > 0
        ? TimeSpan.FromSeconds(sec) : TimeSpan.FromMinutes(10);
    readonly DispatcherTimer _remoteTimer = new() { Interval = RemoteEvery };
    DateTime _lastRemoteCheck;
    bool _checkingRemote;
    Library _pendingLib;
    readonly DispatcherTimer _updateTimer = new() { Interval = TimeSpan.FromMinutes(30) };
    // STREAMPLAY_CONTA_SECONDS só existe para testes; o normal é conferir a conta a cada 15 minutos
    static readonly TimeSpan ContaEvery = int.TryParse(Environment.GetEnvironmentVariable("STREAMPLAY_CONTA_SECONDS"), out var cs) && cs > 0
        ? TimeSpan.FromSeconds(cs) : TimeSpan.FromMinutes(15);
    readonly DispatcherTimer _contaTimer = new() { Interval = ContaEvery };
    DateTime _ultimaConta;
    bool _verificandoConta;
    bool _restarting, _updating;
    bool _fullscreen;
    WindowState _preFull;
    Rect _preBounds = Rect.Empty;
    DateTime _splashStart;

    public MainWindow()
    {
        InitializeComponent();
        I = this;
        _reloadTimer.Tick += async (_, _) => { _reloadTimer.Stop(); await LoadLibrary(quiet: true); };
        _searchTimer.Tick += (_, _) => { _searchTimer.Stop(); (PageHost.Content as IPage)?.Search(SearchBox.Text.Trim()); };
        _toastTimer.Tick += (_, _) => { _toastTimer.Stop(); UI.Fade(ToastBox, 0, 300); };
        _remoteTimer.Tick += async (_, _) => await CheckRemote();
        Activated += async (_, _) => { if (DateTime.Now - _lastRemoteCheck > TimeSpan.FromTicks(Math.Min(RemoteEvery.Ticks, TimeSpan.FromMinutes(3).Ticks))) await CheckRemote(); };
        Loaded += async (_, _) =>
        {
            StartSplash();
            Updater.Cleanup();
            if (Updater.Enabled && await StartupUpdate()) return;
            if (Updater.Enabled) _updateTimer.Start();
            if (await GarantirConta()) await LoadLibrary();
            TesteRolagem.Talvez(this);
        };
        _contaTimer.Tick += async (_, _) => await ConferirConta();
        // guia de programação: relê a cada 30 min (a grade anda e o arquivo é renovado a cada 6 h)
        var guiaTimer = new DispatcherTimer { Interval = TimeSpan.FromMinutes(30) };
        guiaTimer.Tick += (_, _) => { if (Lib != null) _ = Guia.CarregarAsync(Lib); };
        guiaTimer.Start();
        Activated += async (_, _) => { if (DateTime.Now - _ultimaConta > TimeSpan.FromTicks(Math.Min(ContaEvery.Ticks, TimeSpan.FromMinutes(10).Ticks))) await ConferirConta(); };
        _updateTimer.Tick += async (_, _) => await CheckUpdates(manual: false);
        StateChanged += (_, _) => MaxBtn.Content = WindowState == WindowState.Maximized && !_fullscreen ? "" : "";
        PreviewKeyDown += OnKey;
        Activated += (_, _) => Dispatcher.BeginInvoke(SyncTopmost);
        Deactivated += (_, _) => Dispatcher.BeginInvoke(SyncTopmost);
        Closing += (_, _) =>
        {
            _player?.Shutdown();
            Store.Save();
            // atualização baixada e não instalada: instala ao fechar (abre já na versão nova da próxima vez)
            if (!_restarting && Updater.Ready != null && Updater.CanInstall()) Updater.Install(restart: false);
        };
    }

    // ---------- Lista ----------

    public async Task LoadLibrary(bool quiet = false)
    {
        if (string.IsNullOrWhiteSpace(Store.D.Source)) { ShowSetup(null); EndSplash(); return; }
        try
        {
            var lib = await M3U.LoadAsync(Store.D.Source);
            SetLibrary(lib);
            Watch();
            if (quiet) Toast("Lista atualizada  •  " + lib.Summary);
        }
        catch (Exception ex)
        {
            if (quiet && Lib != null) Toast("Não consegui recarregar a lista: " + ex.Message);
            else ShowSetup("Não foi possível abrir a lista: " + ex.Message);
        }
        EndSplash();
    }

    void SetLibrary(Library lib)
    {
        Lib = lib;
        _ = Guia.CarregarAsync(lib);   // guia de programação dos canais (em segundo plano)
        _movies = _series = null;
        _channels = null;
        if (NaTelaDeConta) return;   // lista recarregou por trás da tela de login: só aparece depois de entrar
        TopBar.Visibility = Visibility.Visible;
        Navigate(_tab ?? Store.D.Tab ?? "movies");
    }

    // Lista online (GitHub, Firebase Hosting, Dropbox...): confere novidades a cada 10 min e ao voltar para o app.
    async Task CheckRemote()
    {
        var src = Store.D.Source;
        if (_checkingRemote || Lib == null || !M3U.IsUrl(src ?? "")) return;
        _checkingRemote = true;
        _lastRemoteCheck = DateTime.Now;
        try
        {
            var lib = await M3U.LoadIfChangedAsync(src);
            if (lib != null && src == Store.D.Source) ApplyRemote(lib);
        }
        catch (Exception ex) { App.Log(ex); }
        finally { _checkingRemote = false; }
    }

    void ApplyRemote(Library lib)
    {
        // não troca a tela embaixo de quem está com os detalhes abertos ou buscando: aplica depois
        if (OverlayOpen || SearchBox.Text.Length > 0) { _pendingLib = lib; return; }
        _pendingLib = null;
        SetLibrary(lib);
        Toast("Lista atualizada  •  " + lib.Summary);
    }

    void Watch()
    {
        _watch?.Dispose();
        _watch = null;
        var src = Store.D.Source;
        _lastRemoteCheck = DateTime.Now;
        if (M3U.IsUrl(src ?? "")) _remoteTimer.Start(); else _remoteTimer.Stop();
        if (M3U.IsUrl(src ?? "") || !File.Exists(src)) return;
        try
        {
            _watch = new FileSystemWatcher(Path.GetDirectoryName(Path.GetFullPath(src)))
            {
                NotifyFilter = NotifyFilters.LastWrite | NotifyFilters.Size | NotifyFilters.FileName,
            };
            _watch.Filters.Add(Path.GetFileName(src));
            if (M3U.Companheira(src) is { } canais) _watch.Filters.Add(Path.GetFileName(canais));   // canais.m3u na mesma pasta
            _watch.EnableRaisingEvents = true;
            FileSystemEventHandler h = (_, _) => Dispatcher.BeginInvoke(() => { _reloadTimer.Stop(); _reloadTimer.Start(); });
            _watch.Changed += h;
            _watch.Created += h;
            _watch.Renamed += (_, _) => Dispatcher.BeginInvoke(() => { _reloadTimer.Stop(); _reloadTimer.Start(); });
        }
        catch { }
    }

    public void ShowSetup(string error)
    {
        if (NaTelaDeConta) return;
        TopBar.Visibility = Visibility.Collapsed;
        CloseOverlay();
        PageHost.Content = new SetupView(error);
    }

    public void LeaveSetup()
    {
        if (Lib == null || NaTelaDeConta) return;
        TopBar.Visibility = Visibility.Visible;
        Navigate(_tab ?? Store.D.Tab ?? "movies");
    }

    public async void UseSource(string source)
    {
        Store.D.Source = source;
        Store.Save();
        _tab = null;
        await LoadLibrary();
    }

    // ---------- Conta (PLAYGER) ----------

    public Conta.Resultado ContaAtual { get; private set; }
    bool NaTelaDeConta => PageHost.Content is LoginView;
    // sem internet, quem já entrou continua usando por até 3 dias desde a última confirmação
    static readonly TimeSpan Tolerancia = TimeSpan.FromDays(3);

    /// <summary>Na abertura: confere a conta salva. Devolve false quando a tela de login ficou na frente.</summary>
    async Task<bool> GarantirConta()
    {
        if (string.IsNullOrEmpty(Store.D.AcessoId)) { ShowLogin(null); EndSplash(); return false; }
        SplashText.Text = "Verificando seu acesso...";
        var r = await Conta.VerificarAsync(Store.D.AcessoId);
        SplashText.Text = "Carregando sua lista...";
        if (r.Estado == Conta.Estado.Liberado) { AceitarConta(r); AvisarVencimento(r); return true; }
        if (r.Estado == Conta.Estado.SemConexao && DateTime.Now - Store.D.ContaOk < Tolerancia)
        {
            _ultimaConta = DateTime.Now;
            _contaTimer.Start();
            Toast("Sem internet para confirmar seu acesso agora. Vou tentar de novo daqui a pouco.");
            return true;
        }
        MostrarConta(r);
        EndSplash();
        return false;
    }

    /// <summary>Conferência periódica (timer e ao voltar para o app): bloqueia na hora se o acesso caiu.</summary>
    async Task ConferirConta()
    {
        if (_verificandoConta || string.IsNullOrEmpty(Store.D.AcessoId) || NaTelaDeConta || ContaAtual == null && Store.D.ContaOk == default) return;
        _verificandoConta = true;
        _ultimaConta = DateTime.Now;
        try
        {
            var r = await Conta.VerificarAsync(Store.D.AcessoId);
            if (NaTelaDeConta) return;
            if (r.Estado == Conta.Estado.Liberado) AceitarConta(r);
            else if (r.Estado != Conta.Estado.SemConexao || DateTime.Now - Store.D.ContaOk > Tolerancia) MostrarConta(r);
        }
        finally { _verificandoConta = false; }
    }

    void AceitarConta(Conta.Resultado r)
    {
        ContaAtual = r;
        Store.D.ContaOk = DateTime.Now;
        if (!string.IsNullOrEmpty(r.Acesso?.Login)) Store.D.Login = r.Acesso.Login;
        Store.Save();
        _ultimaConta = DateTime.Now;
        _contaTimer.Start();
    }

    void AvisarVencimento(Conta.Resultado r)
    {
        var aviso = Conta.Aviso(r);
        if (aviso != null) Dispatcher.BeginInvoke(() => Toast(aviso), DispatcherPriority.Background);
    }

    void MostrarConta(Conta.Resultado r)
    {
        if (r.Estado == Conta.Estado.Invalido) SairDaConta("Sua senha mudou ou o acesso foi removido. Entre de novo.");
        else if (r.Estado == Conta.Estado.Bloqueado) ShowLogin(new LoginView(r));
        else ShowLogin(new LoginView(r.Mensagem));
    }

    void ShowLogin(LoginView view)
    {
        _contaTimer.Stop();
        ContaAtual = null;
        CloseSearch(clear: true);
        TopBar.Visibility = Visibility.Collapsed;
        OverlayHost.Children.Clear();
        OverlayHost.Visibility = Visibility.Collapsed;
        PageHost.Content = view ?? new LoginView((string)null);
        _player?.Close();   // depois de trocar a página, para o player não reabrir a barra de cima
    }

    /// <summary>A tela de login confirmou a conta.</summary>
    public async void ContaLiberada(Conta.Resultado r)
    {
        AceitarConta(r);
        PageHost.Content = null;
        if (Lib != null) { TopBar.Visibility = Visibility.Visible; Navigate(_tab ?? Store.D.Tab ?? "movies"); }
        else await LoadLibrary();
        AvisarVencimento(r);
    }

    public void SairDaConta(string aviso)
    {
        Store.D.AcessoId = null;
        Store.D.ContaOk = default;
        Store.Save();
        ShowLogin(new LoginView(aviso));
    }

    // ---------- Navegação ----------

    void Nav_Click(object sender, RoutedEventArgs e) => Navigate((string)((FrameworkElement)sender).Tag);

    // seleção pelo teclado / leitor de tela também troca de aba
    void Nav_Checked(object sender, RoutedEventArgs e)
    {
        var tab = (string)((FrameworkElement)sender).Tag;
        if (!_navigating && tab != _tab) Navigate(tab);
    }

    bool _navigating;

    public void Navigate(string tab)
    {
        if (Lib == null || NaTelaDeConta) return;
        _tab = tab;
        Store.D.Tab = tab;
        _navigating = true;
        NavChannels.IsChecked = tab == "channels";
        NavMovies.IsChecked = tab == "movies";
        NavSeries.IsChecked = tab == "series";
        NavList.IsChecked = tab == "mylist";
        _navigating = false;
        CloseSearch(clear: true);
        FrameworkElement view = tab switch
        {
            "channels" => _channels ??= new ChannelsView(Lib),
            "series" => _series ??= new BrowseView(Lib, Kind.Series),
            "mylist" => new BrowseView(Lib, null),
            _ => _movies ??= new BrowseView(Lib, Kind.Movie),
        };
        (view as IPage)?.Refresh();
        PageHost.Content = view;
        SetTopSolid(tab == "channels" ? 1 : 0);
    }

    public void SetTopSolid(double v) => TopSolid.Opacity = Math.Clamp(v, 0, 1);

    // ---------- Busca ----------

    void SearchBtn_Click(object sender, RoutedEventArgs e)
    {
        if (SearchBox.Width > 0 && SearchBox.Text.Length > 0) { SearchBox.Focus(); return; }
        OpenSearch();
    }

    void OpenSearch()
    {
        SearchWrap.BorderThickness = new Thickness(1);
        SearchWrap.Background = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromArgb(0xCC, 0, 0, 0));
        SearchBox.BeginAnimation(WidthProperty, new DoubleAnimation(240, TimeSpan.FromMilliseconds(220)) { EasingFunction = new QuadraticEase() });
        SearchBox.Focus();
    }

    void CloseSearch(bool clear)
    {
        if (clear) SearchBox.Text = "";
        if (SearchBox.Text.Length > 0) return;
        SearchWrap.BorderThickness = new Thickness(0);
        SearchWrap.Background = System.Windows.Media.Brushes.Transparent;
        SearchBox.BeginAnimation(WidthProperty, new DoubleAnimation(0, TimeSpan.FromMilliseconds(180)));
    }

    void SearchBox_TextChanged(object sender, TextChangedEventArgs e) { _searchTimer.Stop(); _searchTimer.Start(); }
    void SearchBox_LostFocus(object sender, KeyboardFocusChangedEventArgs e) => CloseSearch(clear: false);

    // ---------- Sobreposições ----------

    public void ShowDetails(Item item) => ShowOverlay(new DetailsView(item));
    public void ShowSettings() => ShowOverlay(new SettingsView());
    void Settings_Click(object sender, RoutedEventArgs e) => ShowSettings();

    public void ShowOverlay(FrameworkElement el)
    {
        OverlayHost.Children.Clear();
        OverlayHost.Children.Add(el);
        OverlayHost.Visibility = Visibility.Visible;
        OverlayHost.Opacity = 0;
        UI.Fade(OverlayHost, 1, 180);
    }

    public void CloseOverlay()
    {
        if (OverlayHost.Visibility != Visibility.Visible) return;
        UI.Fade(OverlayHost, 0, 150, () =>
        {
            if (OverlayHost.Opacity > 0.01) return;
            OverlayHost.Children.Clear();
            OverlayHost.Visibility = Visibility.Collapsed;
            if (_pendingLib != null) ApplyRemote(_pendingLib);
        });
    }

    bool OverlayOpen => OverlayHost.Visibility == Visibility.Visible && OverlayHost.Children.Count > 0;

    // ---------- Player ----------

    public async void Play(PlayRequest req)
    {
        if (req == null || string.IsNullOrWhiteSpace(req.Url)) { Toast("Este item não tem link de vídeo."); return; }
        if (NaTelaDeConta) return;
        if (_player != null) { _player.Switch(req); return; }
        bool esperando = !App.VlcReady.IsCompleted;
        if (esperando) Toast("Preparando o player (só na primeira vez)...", fixo: true);
        try { await App.VlcReady; }
        catch (Exception ex) { App.Log(ex); Toast("Não foi possível iniciar o motor do VLC: " + ex.Message); return; }
        if (esperando) { _toastTimer.Stop(); UI.Fade(ToastBox, 0, 300); }
        if (_player != null) { _player.Switch(req); return; }
        _player = new PlayerView(req);
        PlayerHost.Children.Add(_player);
        PlayerHost.Visibility = Visibility.Visible;
        TopBar.Visibility = Visibility.Collapsed;
        WinBtns.Visibility = Visibility.Collapsed;
        if (Store.D.Fullscreen) SetFullscreen(true);
    }

    /// <summary>Chamado assim que o usuário sai do player (o VLC ainda pode estar parando).</summary>
    public void PlayerLeaving(PlayerView p)
    {
        if (_player != p) return;
        _player = null;
        if (_fullscreen) SetFullscreen(false);
        TopBar.Visibility = Lib != null && PageHost.Content is not SetupView && !NaTelaDeConta ? Visibility.Visible : Visibility.Collapsed;
        WinBtns.Visibility = Visibility.Visible;
        (PageHost.Content as IPage)?.Refresh();
        foreach (var d in OverlayHost.Children.OfType<DetailsView>()) d.Refresh();
        Activate();
        Focus();
    }

    public void PlayerGone(PlayerView p)
    {
        PlayerHost.Children.Remove(p);
        if (PlayerHost.Children.Count == 0) PlayerHost.Visibility = Visibility.Collapsed;
    }

    public bool IsFullscreen => _fullscreen;

    public void SetFullscreen(bool on)
    {
        if (_fullscreen == on) return;
        _fullscreen = on;
        var hwnd = new WindowInteropHelper(this).Handle;
        if (on)
        {
            // Igual Chrome/VLC: sem moldura, sem redimensionar e exatamente do tamanho do monitor.
            // Só assim o Windows reconhece como tela cheia e esconde a barra de tarefas.
            _preFull = WindowState;
            _preBounds = WindowState == WindowState.Normal ? new Rect(Left, Top, Width, Height) : RestoreBounds;
            WindowChrome.SetWindowChrome(this, null);
            ResizeMode = ResizeMode.NoResize;
            if (WindowState != WindowState.Normal) WindowState = WindowState.Normal;
            var mi = new MONITORINFO { cbSize = Marshal.SizeOf<MONITORINFO>() };
            if (GetMonitorInfo(MonitorFromWindow(hwnd, 2), ref mi))
            {
                var r = mi.rcMonitor;
                SetWindowPos(hwnd, IntPtr.Zero, r.Left, r.Top, r.Right - r.Left, r.Bottom - r.Top, 0x0004 | 0x0020); // NOZORDER | FRAMECHANGED
            }
        }
        else
        {
            ResizeMode = ResizeMode.CanResize;
            WindowChrome.SetWindowChrome(this, NewChrome());
            if (!_preBounds.IsEmpty)
            {
                Left = _preBounds.Left; Top = _preBounds.Top;
                Width = _preBounds.Width; Height = _preBounds.Height;
            }
            if (_preFull == WindowState.Maximized) WindowState = WindowState.Maximized;
        }
        SyncTopmost();
    }

    static WindowChrome NewChrome() => new()
    {
        CaptionHeight = 68, ResizeBorderThickness = new Thickness(6),
        GlassFrameThickness = new Thickness(0), CornerRadius = new CornerRadius(0), UseAeroCaptionButtons = false,
    };

    // Os controles do player ficam numa janela transparente à parte, e o Windows não trata o conjunto
    // como tela cheia: a barra de tarefas aparecia por trás deles. Em tela cheia e com o app em foco,
    // a janela fica acima da barra; ao trocar de app (Alt+Tab) ela volta ao normal.
    public void SyncTopmost()
    {
        bool on = false;
        if (_fullscreen)
        {
            GetWindowThreadProcessId(GetForegroundWindow(), out uint pid);
            on = pid == (uint)Environment.ProcessId;
        }
        if (Topmost != on) Topmost = on;
        foreach (Window w in OwnedWindows)
            if (w.Topmost != on) w.Topmost = on;
    }

    public void StartDrag()
    {
        if (_fullscreen) return;
        var h = new WindowInteropHelper(this).Handle;
        ReleaseCapture();
        SendMessage(h, 0x00A1, (IntPtr)2, IntPtr.Zero); // WM_NCLBUTTONDOWN, HTCAPTION
    }

    // ---------- Teclado ----------

    void OnKey(object sender, KeyEventArgs e)
    {
        if (_player != null) { if (_player.HandleKey(e.Key)) e.Handled = true; return; }
        bool ctrl = Keyboard.Modifiers.HasFlag(ModifierKeys.Control);
        if (e.Key == Key.Escape)
        {
            if (OverlayOpen) { CloseOverlay(); e.Handled = true; }
            else if (SearchBox.IsKeyboardFocused || SearchBox.Text.Length > 0) { CloseSearch(clear: true); Keyboard.ClearFocus(); e.Handled = true; }
        }
        else if (ctrl && e.Key == Key.F && Lib != null && TopBar.IsVisible) { OpenSearch(); e.Handled = true; }
        else if (e.Key == Key.F5 && Lib != null && !NaTelaDeConta) { _ = LoadLibrary(quiet: true); e.Handled = true; }
    }

    // ---------- Atualização automática ----------

    public event Action UpdateStatusChanged;
    public string UpdateStatus { get; private set; } = "";

    void SetUpdateStatus(string text)
    {
        UpdateStatus = text;
        UpdateStatusChanged?.Invoke();
    }

    // Na tela de abertura: se tiver versão nova, baixa com progresso e reabre já atualizado.
    async Task<bool> StartupUpdate()
    {
        try
        {
            SplashText.Text = "Procurando atualizações...";
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(6));
            var r = await Updater.CheckAsync(timeout.Token);
            if (r == null || !Updater.CanInstall()) { SplashText.Text = "Carregando sua lista..."; return false; }

            var v = r.Version.ToString(3);
            SplashText.BeginAnimation(OpacityProperty, null);
            SplashText.Opacity = 1;
            SplashText.Text = $"Baixando a versão {v}...";
            SplashBar.Visibility = Visibility.Visible;
            var last = DateTime.Now;
            using var stall = new CancellationTokenSource();
            var progress = new Progress<double>(p =>
            {
                last = DateTime.Now;
                SplashFill.Width = p * SplashBar.Width;
                SplashText.Text = $"Baixando a versão {v}...  {(int)(p * 100)}%";
            });
            var download = Updater.DownloadAsync(r, progress, stall.Token);
            while (!download.IsCompleted)
            {
                await Task.WhenAny(download, Task.Delay(1000));
                if ((DateTime.Now - last).TotalSeconds > 25) stall.Cancel();   // download parado
            }
            await download;
            SplashText.Text = "Instalando atualização...";
            await Task.Delay(600);
            if (Updater.Install(restart: true))
            {
                _restarting = true;
                Close();
                return true;
            }
        }
        catch (Exception ex)
        {
            App.Log(ex);
            SplashText.Text = "Não deu pra atualizar agora. Abrindo...";
            await Task.Delay(1100);
        }
        SplashBar.Visibility = Visibility.Collapsed;
        SplashText.Text = "Carregando sua lista...";
        return false;
    }

    // Com o app aberto: baixa em segundo plano e avisa que dá para reiniciar.
    public async Task CheckUpdates(bool manual)
    {
        if (_updating) return;
        _updating = true;
        try
        {
            if (Updater.Ready != null)
            {
                SetUpdateStatus($"A versão {Updater.Ready.Version.ToString(3)} está pronta para instalar.");
                ShowUpdateBanner();
                return;
            }
            SetUpdateStatus("Procurando atualizações...");
            var r = await Updater.CheckAsync();
            if (r == null) { SetUpdateStatus($"Você está na versão mais recente ({Updater.CurrentText})."); return; }
            var v = r.Version.ToString(3);
            if (!Updater.Enabled || !Updater.CanInstall())
            {
                SetUpdateStatus($"A versão {v} está disponível. Baixe pelo GitHub.");
                ShowUpdateBanner(r, download: true);
                return;
            }
            await Updater.DownloadAsync(r, new Progress<double>(p => SetUpdateStatus($"Baixando a versão {v}...  {(int)(p * 100)}%")));
            SetUpdateStatus($"A versão {v} está pronta para instalar.");
            ShowUpdateBanner();
        }
        catch (Exception ex)
        {
            App.Log(ex);
            if (manual) SetUpdateStatus("Não foi possível procurar atualizações agora. Confira sua internet.");
        }
        finally { _updating = false; }
    }

    Updater.Release _bannerRelease;
    bool _bannerDownload;

    void ShowUpdateBanner(Updater.Release r = null, bool download = false)
    {
        _bannerRelease = r ?? Updater.Ready;
        _bannerDownload = download;
        if (_bannerRelease == null) return;
        var v = _bannerRelease.Version.ToString(3);
        UpdateTitle.Text = $"StreamPLAY {v} disponível";
        UpdateText.Text = download
            ? "Esta pasta não permite atualizar sozinho. Baixe a versão nova pelo GitHub."
            : "A atualização já foi baixada. Reinicie para usar, ou ela será instalada quando você fechar o app.";
        UpdateNowBtn.Content = download ? "Baixar no GitHub" : "Reiniciar e atualizar";
        UpdateBanner.Visibility = Visibility.Visible;
    }

    public void InstallUpdateNow()
    {
        if (Updater.Ready == null) return;
        _player?.Shutdown();
        Store.Save();
        if (Updater.Install(restart: true)) { _restarting = true; Close(); }
        else Toast("Não consegui instalar a atualização. Baixe a versão nova pelo GitHub.");
    }

    void UpdateNow_Click(object sender, RoutedEventArgs e)
    {
        if (_bannerDownload)
        {
            try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(Updater.ReleasesPage) { UseShellExecute = true }); } catch { }
            UpdateBanner.Visibility = Visibility.Collapsed;
            return;
        }
        InstallUpdateNow();
    }

    void UpdateLater_Click(object sender, RoutedEventArgs e)
    {
        UpdateBanner.Visibility = Visibility.Collapsed;
        if (!_bannerDownload) Toast("Tudo certo: a atualização será instalada quando você fechar o StreamPLAY.");
    }

    // ---------- Aviso / abertura ----------

    /// <summary>Aviso rápido no rodapé. Com fixo, fica até outro aviso (ou o código) tirar.</summary>
    public void Toast(string text, bool fixo = false)
    {
        ToastText.Text = text;
        UI.Fade(ToastBox, 1, 200);
        _toastTimer.Stop();
        if (!fixo) _toastTimer.Start();
    }

    void StartSplash()
    {
        _splashStart = DateTime.Now;
        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        SplashLogo.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(500)));
        SplashScale.BeginAnimation(System.Windows.Media.ScaleTransform.ScaleXProperty, new DoubleAnimation(0.86, 1, TimeSpan.FromMilliseconds(1100)) { EasingFunction = ease });
        SplashScale.BeginAnimation(System.Windows.Media.ScaleTransform.ScaleYProperty, new DoubleAnimation(0.86, 1, TimeSpan.FromMilliseconds(1100)) { EasingFunction = ease });
        SplashText.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(400)) { BeginTime = TimeSpan.FromMilliseconds(900) });
    }

    async void EndSplash()
    {
        if (Splash.Visibility != Visibility.Visible) return;
        var wait = 1150 - (DateTime.Now - _splashStart).TotalMilliseconds;
        if (wait > 0) await Task.Delay((int)wait);
        UI.Fade(Splash, 0, 380, () => Splash.Visibility = Visibility.Collapsed);
    }

    // ---------- Janela ----------

    void Min_Click(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;
    void Max_Click(object sender, RoutedEventArgs e) => WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;
    void Close_Click(object sender, RoutedEventArgs e) => Close();

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);
        HwndSource.FromHwnd(new WindowInteropHelper(this).Handle)?.AddHook(WndProc);
    }

    IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        // A área onde o VLC desenha (VideoHwndHost do LibVLCSharp) é um controle "static" do Windows: ele pede a cor
        // de fundo à janela-mãe e, sem resposta, pinta o cinza-claro padrão (#F0F0F0). Isso aparecia como quadrados e
        // faixas brancas antes do primeiro quadro do vídeo e quando a tela mudava de tamanho. Responde preto.
        if (msg == 0x0138) // WM_CTLCOLORSTATIC
        {
            SetBkColor(wParam, 0);
            handled = true;
            return GetStockObject(4); // BLACK_BRUSH
        }
        if (msg == 0x0024) // WM_GETMINMAXINFO: maximizado respeita a barra de tarefas; tela cheia cobre o monitor inteiro
        {
            var mmi = Marshal.PtrToStructure<MINMAXINFO>(lParam);
            var mon = MonitorFromWindow(hwnd, 2);
            var mi = new MONITORINFO { cbSize = Marshal.SizeOf<MONITORINFO>() };
            if (mon != IntPtr.Zero && GetMonitorInfo(mon, ref mi))
            {
                var area = _fullscreen ? mi.rcMonitor : mi.rcWork;
                mmi.ptMaxPosition.X = area.Left - mi.rcMonitor.Left;
                mmi.ptMaxPosition.Y = area.Top - mi.rcMonitor.Top;
                mmi.ptMaxSize.X = area.Right - area.Left;
                mmi.ptMaxSize.Y = area.Bottom - area.Top;
                Marshal.StructureToPtr(mmi, lParam, true);
            }
        }
        return IntPtr.Zero;
    }

    [StructLayout(LayoutKind.Sequential)] struct POINT { public int X, Y; }
    [StructLayout(LayoutKind.Sequential)] struct RECT { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)]
    struct MINMAXINFO { public POINT ptReserved, ptMaxSize, ptMaxPosition, ptMinTrackSize, ptMaxTrackSize; }
    [StructLayout(LayoutKind.Sequential)]
    struct MONITORINFO { public int cbSize; public RECT rcMonitor, rcWork; public uint dwFlags; }

    [DllImport("gdi32.dll")] static extern IntPtr GetStockObject(int obj);
    [DllImport("gdi32.dll")] static extern uint SetBkColor(IntPtr hdc, uint color);
    [DllImport("user32.dll")] static extern IntPtr MonitorFromWindow(IntPtr hwnd, uint flags);
    [DllImport("user32.dll")] static extern bool GetMonitorInfo(IntPtr hMonitor, ref MONITORINFO lpmi);
    [DllImport("user32.dll")] static extern bool ReleaseCapture();
    [DllImport("user32.dll")] static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll")] static extern IntPtr SendMessage(IntPtr hWnd, int msg, IntPtr wParam, IntPtr lParam);
}
