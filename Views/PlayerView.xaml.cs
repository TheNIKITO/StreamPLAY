using System.Globalization;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using System.Windows.Threading;
using LibVLCSharp.Shared;
using LibVLCSharp.Shared.Structures;
using MediaPlayer = LibVLCSharp.Shared.MediaPlayer;

namespace StreamPLAY;

/// <summary>Player nativo (VLC) com controles no estilo da Netflix.</summary>
public partial class PlayerView : UserControl
{
    PlayRequest _req;
    MediaPlayer _mp;
    Media _media;
    Episode _next;

    readonly DispatcherTimer _tick = new() { Interval = TimeSpan.FromMilliseconds(250) };
    readonly DispatcherTimer _clickTimer = new() { Interval = TimeSpan.FromMilliseconds(260) };
    DateTime _lastMove = DateTime.Now, _lastSave = DateTime.Now, _volLeft, _pausedAt, _resumeShownAt, _nextShownAt, _startedAt;
    Point _lastMouse;
    bool _chromeShown = true, _closing, _seeking, _volDrag, _muted, _creditsDismissed, _nextShown, _pausedInfo, _errorShown;
    double _seekFrac;
    volatile float _cache;
    long _lastTime = -1;
    int _retries;
    DateTime _playingAt, _vigiaEspera;
    bool _vigiado, _simulado;
    long _reabrirEm = -1;
    string _panel, _aspect = "original";

    static readonly CultureInfo Inv = CultureInfo.InvariantCulture;

    public PlayerView(PlayRequest req)
    {
        InitializeComponent();
        _req = req;
        Loaded += OnLoaded;
        _tick.Tick += (_, _) => Tick();
        _clickTimer.Tick += (_, _) => { _clickTimer.Stop(); TogglePlay(); };
        Ui.MouseMove += Ui_MouseMove;
        Ui.MouseLeftButtonDown += Ui_MouseDown;
        Ui.MouseWheel += (_, e) => { Vol(e.Delta > 0 ? 5 : -5); e.Handled = true; };
        Ui.PreviewKeyDown += (_, e) => { if (HandleKey(e.Key)) e.Handled = true; };
        Ui.Loaded += (_, _) => NoActivate();
        Ui.SizeChanged += (_, _) => { LayoutTitle(); if (_aspect == "fill") ApplyAspect(); };
        LeftBtns.SizeChanged += (_, _) => LayoutTitle();
        RightBtns.SizeChanged += (_, _) => LayoutTitle();
        VolPop.MouseLeave += (_, _) => _volLeft = DateTime.Now;
        VolBtn.MouseLeave += (_, _) => _volLeft = DateTime.Now;
    }

    void OnLoaded(object sender, RoutedEventArgs e)
    {
        if (_mp != null || _closing) return;
        // A aceleração de vídeo vai como opção de cada mídia (automática, igual ao VLC) — ver MakeMedia.
        _mp = new MediaPlayer(App.VLC) { EnableKeyInput = false, EnableMouseInput = false };
        _mp.Playing += (_, _) => Dispatcher.BeginInvoke(OnPlaying);
        _mp.Paused += (_, _) => Dispatcher.BeginInvoke(() => { _pausedAt = DateTime.Now; UpdatePlayIcon(); });
        _mp.Buffering += (_, a) => _cache = a.Cache;
        _mp.EndReached += (_, _) => Dispatcher.BeginInvoke(OnEnded);
        _mp.EncounteredError += (_, _) => Dispatcher.BeginInvoke(OnError);
        Video.MediaPlayer = _mp;   // o fundo preto da área do vídeo vem da MainWindow (WM_CTLCOLORSTATIC)
        Start(_req);
        _tick.Start();
        SpinRot.BeginAnimation(RotateTransform.AngleProperty, new DoubleAnimation(0, 360, TimeSpan.FromSeconds(0.9)) { RepeatBehavior = RepeatBehavior.Forever });
        LiveDot.BeginAnimation(OpacityProperty, new DoubleAnimation(1, 0.25, TimeSpan.FromSeconds(0.8)) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever });
        Dispatcher.BeginInvoke(DispatcherPriority.Input, () => { Ui.Focus(); Keyboard.Focus(Ui); });
    }

    // Os controles ficam numa janela transparente separada (por cima do vídeo do VLC). Se ela virar a
    // janela ativa, o Windows deixa de tratar o app como tela cheia e mostra a barra de tarefas por cima
    // do vídeo. Com WS_EX_NOACTIVATE os cliques continuam chegando, mas a janela principal segue ativa
    // (e recebe o teclado).
    void NoActivate()
    {
        if (System.Windows.PresentationSource.FromVisual(Ui) is not System.Windows.Interop.HwndSource src) return;
        long ex = GetWindowLongPtr(src.Handle, -20).ToInt64();
        if ((ex & 0x08000000) != 0) return;
        SetWindowLongPtr(src.Handle, -20, new IntPtr(ex | 0x08000000));
        MainWindow.I.Activate();
    }

    [System.Runtime.InteropServices.DllImport("user32.dll")] static extern IntPtr GetWindowLongPtr(IntPtr h, int i);
    [System.Runtime.InteropServices.DllImport("user32.dll")] static extern IntPtr SetWindowLongPtr(IntPtr h, int i, IntPtr v);

    // ---------- Início / troca ----------

    public void Switch(PlayRequest req)
    {
        if (_closing || _mp == null) return;
        SaveProgress();
        Start(req);
    }

    void Start(PlayRequest req)
    {
        _req = req;
        _creditsDismissed = _nextShown = _errorShown = false;
        _cache = 0;
        _lastTime = -1;
        _startedAt = DateTime.Now;
        _playingAt = _vigiaEspera = default;
        _vigiado = false;
        _simulado = _reabrirEm >= 0;   // na reabertura da vigia, não provoca o defeito de novo
        NextCard.Visibility = ResumeToast.Visibility = ErrorBox.Visibility = Visibility.Collapsed;
        HidePausedInfo();
        ClosePanel();
        ApplyMode();

        var media = MakeMedia(req);
        var old = _media;
        _media = media;
        _mp.Play(media);
        old?.Dispose();
        if (_aspect != "original") ApplyAspect();
        if (req.IsLive) Store.AddRecent(req.Item.Key);
        ShowChrome();
    }

    Media MakeMedia(PlayRequest req)
    {
        var url = req.Url.Trim();
        bool remote = url.Contains("://");
        var media = remote ? new Media(App.VLC, url, FromType.FromLocation) : new Media(App.VLC, url, FromType.FromPath);
        media.AddOption($":network-caching={Store.D.Caching}");
        // "any" = o VLC escolhe a aceleração que funciona neste PC e cai para o processador se precisar (o padrão do VLC)
        media.AddOption(SemAceleracao.Contains(url) ? ":avcodec-hw=none" : ":avcodec-hw=any");
        if (req.IsLive) { media.AddOption(":http-reconnect"); media.AddOption($":live-caching={Store.D.Caching}"); }
        foreach (var o in req.Options) if (!string.IsNullOrWhiteSpace(o)) media.AddOption(":" + o.Trim().TrimStart(':'));

        if (_reabrirEm >= 0)
        {
            if (!req.IsLive) media.AddOption(":start-time=" + (_reabrirEm / 1000.0).ToString("0.0", Inv));
            _reabrirEm = -1;
        }
        else if (!req.IsLive && !req.FromStart)
        {
            var p = Store.GetProgress(req.Key);
            if (p != null && !p.Finished && p.Pos > 15000)
            {
                long at = Math.Max(0, p.Pos - 3000);
                media.AddOption(":start-time=" + (at / 1000.0).ToString("0.0", Inv));
                ResumeText.Text = $"Continuando de {UI.Time(at)}";
                ResumeToast.Visibility = Visibility.Visible;
                ResumeToast.Opacity = 1;
                _resumeShownAt = DateTime.Now;
            }
        }
        req.FromStart = false;
        return media;
    }

    void ApplyMode()
    {
        bool live = _req.IsLive;
        bool series = _req.Episode != null;
        _next = series ? Resume.Next(_req.Episode) : null;
        bool many = live && _req.Channels?.Count > 1;

        SeekRow.Visibility = live ? Visibility.Collapsed : Visibility.Visible;
        LiveRow.Visibility = live ? Visibility.Visible : Visibility.Collapsed;
        RewBtn.Visibility = FwdBtn.Visibility = SpeedBtn.Visibility = live ? Visibility.Collapsed : Visibility.Visible;
        ChPrevBtn.Visibility = ChNextBtn.Visibility = ChannelsBtn.Visibility = many ? Visibility.Visible : Visibility.Collapsed;
        NextBtn.Visibility = _next != null ? Visibility.Visible : Visibility.Collapsed;
        EpisodesBtn.Visibility = series && _req.Item.Episodes.Count > 1 ? Visibility.Visible : Visibility.Collapsed;
        LiveCat.Text = live ? _req.Item.Category : "";

        TitleText.Inlines.Clear();
        TitleText.Inlines.Add(new Run(_req.Title) { FontWeight = FontWeights.Bold });
        if (series) TitleText.Inlines.Add(new Run($"   {_req.Episode.Label}  {_req.Episode.Title}") { Foreground = new SolidColorBrush(Color.FromRgb(0xD0, 0xD0, 0xD0)) });

        PausedTitle.Text = _req.Title;
        PausedSub.Text = series ? $"{_req.Episode.Label}  \"{_req.Episode.Title}\"" : "";
        PausedSub.Visibility = series ? Visibility.Visible : Visibility.Collapsed;
        PausedPlot.Text = (series ? _req.Episode.Plot : null) ?? _req.Item.Plot ?? "";
        SetSeekVisual(0);
        Remaining.Text = "";
        UpdatePlayIcon();
        UpdateVolIcon();
        LayoutTitle();
    }

    void LayoutTitle()
    {
        double side = Math.Max(LeftBtns.ActualWidth, RightBtns.ActualWidth);
        TitleText.MaxWidth = Math.Max(80, Bottom.ActualWidth - 2 * side - 40);
    }

    // ---------- Eventos do VLC ----------

    void OnPlaying()
    {
        if (_mp == null || _closing) return;
        if (_playingAt == default) _playingAt = DateTime.Now;
        ApplyVolume();
        UpdatePlayIcon();
        HidePausedInfo();
    }

    void OnEnded()
    {
        if (_mp == null || _closing) return;
        if (_req.IsLive)
        {
            if (_retries++ < 3) { Retry(1200); return; }
            ShowError("A transmissão foi interrompida", "O canal parou de enviar vídeo. Tente de novo em alguns instantes.");
            return;
        }
        long len = _mp.Length;
        if (len > 0) Store.SetProgress(_req.Key, len, len, _req.ShowKey);
        if (_next != null && Store.D.AutoNext && !_creditsDismissed) { PlayNext(); return; }
        Close();
    }

    void OnError()
    {
        if (_mp == null || _closing) return;
        if (_req.IsLive && _retries++ < 2) { Retry(1500); return; }
        string host = "";
        try { host = _req.Url.Contains("://") ? new Uri(_req.Url).Host : System.IO.Path.GetFileName(_req.Url); } catch { }
        ShowError("Não foi possível reproduzir",
            "O VLC não conseguiu abrir este vídeo. Confira se o link da sua lista está funcionando" + (host.Length > 0 ? $" ({host})." : "."));
    }

    async void Retry(int delay)
    {
        Spinner.Visibility = Visibility.Visible;
        await Task.Delay(delay);
        if (_mp == null || _closing) return;
        var req = _req;
        Start(req);
    }

    void ShowError(string title, string text)
    {
        _errorShown = true;
        ErrorTitle.Text = title;
        ErrorText.Text = text;
        ErrorBox.Visibility = Visibility.Visible;
        Spinner.Visibility = Visibility.Collapsed;
        ShowChrome();
    }

    void Retry_Click(object sender, RoutedEventArgs e) { _retries = 0; Start(_req); }

    // ---------- Laço de atualização ----------

    void Tick()
    {
        if (_mp == null || _closing) return;
        var now = DateTime.Now;
        var st = _mp.State;
        long len = _mp.Length, t = _mp.Time;

        if (st == VLCState.Playing) { ApplyVolume(); Vigiar(now, t); }
        if (st == VLCState.Playing && t > 0 && _lastTime >= 0 && t != _lastTime) _cache = 100;
        _lastTime = t;
        if (st == VLCState.Playing && (now - _startedAt).TotalSeconds > 20) _retries = 0;

        bool busy = !_errorShown && (st == VLCState.Opening || st == VLCState.Buffering || st == VLCState.NothingSpecial || (st == VLCState.Playing && _cache < 100));
        Spinner.Visibility = busy ? Visibility.Visible : Visibility.Collapsed;

        if (!_req.IsLive)
        {
            if (!_seeking && len > 0) SetSeekVisual(t / (double)len);
            if (!_seeking) Remaining.Text = len > 0 ? UI.Time(len - t) : "";
            if (len > 0 && st == VLCState.Playing && (now - _lastSave).TotalSeconds > 5) SaveProgress();

            if (_next != null && !_nextShown && !_creditsDismissed && len > 180000 && t > 0 && len - t < 25000) ShowNextCard();
            if (_nextShown && Store.D.AutoNext)
            {
                double f = (now - _nextShownAt).TotalSeconds / 10.0;
                NextFill.Width = Math.Min(1, f) * NextNowBtn.ActualWidth;
                if (f >= 1) { PlayNext(); return; }
            }
        }

        if (ResumeToast.Visibility == Visibility.Visible && (now - _resumeShownAt).TotalSeconds > 7)
            UI.Fade(ResumeToast, 0, 400, () => { if (ResumeToast.Opacity < 0.05) ResumeToast.Visibility = Visibility.Collapsed; });

        bool overControls = Bottom.IsMouseOver || BackBtn.IsMouseOver || VolPop.IsMouseOver || PanelBox.IsMouseOver || NextCard.IsMouseOver || ErrorBox.IsVisible;
        if (_chromeShown && st == VLCState.Playing && _panel == null && !_seeking && !_volDrag && !overControls && (now - _lastMove).TotalSeconds > 3.2)
            HideChrome();

        if (VolPop.Visibility == Visibility.Visible && !_volDrag && !VolPop.IsMouseOver && !VolBtn.IsMouseOver && (now - _volLeft).TotalMilliseconds > 450)
            VolPop.Visibility = Visibility.Collapsed;

        if (st == VLCState.Paused && !_pausedInfo && _panel == null && (now - _pausedAt).TotalSeconds > 6 && (now - _lastMove).TotalSeconds > 6 && !_req.IsLive)
            ShowPausedInfo();

        FullIcon.Data = (Geometry)FindResource(MainWindow.I.IsFullscreen ? "G.ExitFull" : "G.Full");
        NoActivate();
        MainWindow.I.SyncTopmost();
    }

    // ---------- Vigia dos primeiros segundos ----------
    // Alguns arquivos abrem sem faixa de áudio escolhida, ou com o vídeo sem aparecer (tela preta com som).
    // Nos primeiros segundos o player confere e corrige: escolhe o áudio (de preferência em português) e, se o
    // vídeo não aparecer, reabre no mesmo ponto sem aceleração de vídeo — e lembra disso para esse link.

    static readonly HashSet<string> SemAceleracao = new(StringComparer.OrdinalIgnoreCase);
    // STREAMPLAY_TESTE_DEFEITO=audio|video|vout só existe para testes: provoca o defeito para ver a vigia corrigir
    static readonly string Defeito = Environment.GetEnvironmentVariable("STREAMPLAY_TESTE_DEFEITO");

    void SimularDefeito()
    {
        if (Defeito == "audio") _mp.SetAudioTrack(-1);
        else if (Defeito == "video") _mp.SetVideoTrack(-1);
        App.Log($"Teste: defeito '{Defeito}' provocado (áudio {_mp.AudioTrack}/{_mp.AudioTrackCount}, vídeo {_mp.VideoTrack}/{_mp.VideoTrackCount}).");
    }

    // Vigia até o vídeo ter tocado 8 s de verdade (ou 40 s de relógio, se a internet estiver travando).
    void Vigiar(DateTime now, long t)
    {
        if (_vigiado || _playingAt == default) return;
        double s = (now - _playingAt).TotalSeconds;
        if (Defeito != null && !_simulado && s >= 0.8) { _simulado = true; SimularDefeito(); }
        if (s < 1.5 || now < _vigiaEspera) return;
        if (t > 8000 || s > 40) _vigiado = true;
        // Só mexe nas faixas depois que o vídeo começou a andar: antes disso o VLC ainda pode estar carregando os
        // decodificadores (na primeira vez demora, por causa do antivírus) e a faixa aparece como "nenhuma".
        bool andando = t > 1500 || s > 12;
        try
        {
            if (_mp.Mute) _mp.Mute = false;
            if (andando && _mp.AudioTrack == -1 && _mp.AudioTrackCount > 0 && MelhorAudio() is int a)
            {
                _mp.SetAudioTrack(a);
                App.Log($"Vigia do player: abriu sem faixa de áudio; escolhi a faixa {a}. {_req.Url}");
                _vigiaEspera = now.AddSeconds(3);   // dá tempo da troca fazer efeito antes de conferir de novo
                _vigiado = false;
                return;
            }

            if (_mp.VideoTrackCount > 0)
            {
                if (_mp.VideoTrack == -1)
                {
                    if (andando && SafeTracks(() => _mp.VideoTrackDescription).Where(x => x.Id != -1).Select(x => (int?)x.Id).FirstOrDefault() is int v)
                    {
                        _mp.SetVideoTrack(v);
                        App.Log($"Vigia do player: abriu sem faixa de vídeo; escolhi a faixa {v}. {_req.Url}");
                        _vigiaEspera = now.AddSeconds(3);
                        _vigiado = false;
                        return;
                    }
                }
                else if ((Defeito == "vout" ? 0 : _mp.VoutCount) == 0 && s > 3 && t > 1500 && SemAceleracao.Add(_req.Url.Trim()))
                {
                    // tem vídeo e o tempo está andando, mas nada aparece na tela
                    App.Log($"Vigia do player: vídeo não apareceu; reabrindo em {t / 1000} s sem aceleração. {_req.Url}");
                    _vigiado = true;
                    _reabrirEm = Math.Max(0, t - 1000);
                    Start(_req);
                    return;
                }
            }
        }
        catch (Exception ex) { App.Log(ex); }
    }

    int? MelhorAudio()
    {
        var faixas = SafeTracks(() => _mp.AudioTrackDescription).Where(x => x.Id != -1).ToList();
        if (faixas.Count == 0) return null;
        foreach (var f in faixas)
            if (Regex.IsMatch(f.Name ?? "", @"portugu|\bpt\b|\bpor\b|dublad", RegexOptions.IgnoreCase)) return f.Id;
        return faixas[0].Id;
    }

    void SaveProgress()
    {
        if (_mp == null || _req.IsLive) return;
        long len = _mp.Length, t = _mp.Time;
        _lastSave = DateTime.Now;
        if (len <= 0 || t < 0) return;
        if (t < 10000 && Store.GetProgress(_req.Key) == null) return;
        Store.SetProgress(_req.Key, t, len, _req.ShowKey);
    }

    // ---------- Controles visíveis / ocultos ----------

    void Ui_MouseMove(object sender, MouseEventArgs e)
    {
        var p = e.GetPosition(Ui);
        if (Math.Abs(p.X - _lastMouse.X) < 1 && Math.Abs(p.Y - _lastMouse.Y) < 1) return;
        _lastMouse = p;
        Poke();
    }

    void Poke()
    {
        _lastMove = DateTime.Now;
        if (_pausedInfo) HidePausedInfo();
        if (!_chromeShown) ShowChrome();
    }

    void ShowChrome()
    {
        _chromeShown = true;
        _lastMove = DateTime.Now;
        Chrome.IsHitTestVisible = true;
        UI.Fade(Chrome, 1, 150);
        Ui.Cursor = null;
    }

    void HideChrome()
    {
        _chromeShown = false;
        Chrome.IsHitTestVisible = false;
        VolPop.Visibility = Visibility.Collapsed;
        UI.Fade(Chrome, 0, 450);
        Ui.Cursor = Cursors.None;
    }

    void ShowPausedInfo()
    {
        _pausedInfo = true;
        PausedInfo.Visibility = Visibility.Visible;
        UI.Fade(PausedInfo, 1, 700);
        HideChrome();
    }

    void HidePausedInfo()
    {
        if (!_pausedInfo) return;
        _pausedInfo = false;
        UI.Fade(PausedInfo, 0, 250, () => { if (!_pausedInfo) PausedInfo.Visibility = Visibility.Collapsed; });
    }

    void Ui_MouseDown(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource is not FrameworkElement fe) return;
        var tag = fe.Tag as string;
        if (fe != Ui && fe != PausedInfo && tag != "bg-top") return;
        if (_panel != null) { ClosePanel(); return; }
        if (e.ClickCount >= 2) { _clickTimer.Stop(); ToggleFullscreen(); return; }
        if (tag == "bg-top" && !MainWindow.I.IsFullscreen) { MainWindow.I.StartDrag(); return; }
        _clickTimer.Stop();
        _clickTimer.Start();
    }

    // ---------- Reprodução ----------

    void TogglePlay()
    {
        if (_mp == null) return;
        var st = _mp.State;
        if (st == VLCState.Ended || st == VLCState.Stopped || st == VLCState.Error) { Start(_req); return; }
        bool pause = _mp.IsPlaying;
        _mp.SetPause(pause);
        if (pause) _pausedAt = DateTime.Now;
        FlashIconShow(pause ? "G.Pause" : "G.Play");
        Dispatcher.BeginInvoke(DispatcherPriority.Background, UpdatePlayIcon);
        if (!pause) HidePausedInfo();
        Poke();
    }

    void UpdatePlayIcon()
    {
        if (_mp == null) return;
        bool playing = _mp.State is VLCState.Playing or VLCState.Opening or VLCState.Buffering or VLCState.NothingSpecial;
        PlayIcon.Data = (Geometry)FindResource(playing ? "G.Pause" : "G.Play");
        PlayBtn.ToolTip = playing ? "Pausar (Espaço)" : "Reproduzir (Espaço)";
    }

    void FlashIconShow(string geo)
    {
        FlashIcon.Data = (Geometry)FindResource(geo);
        Flash.BeginAnimation(OpacityProperty, new DoubleAnimation(1, 0, TimeSpan.FromMilliseconds(650)) { EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseIn } });
        var s = new DoubleAnimation(0.85, 1.25, TimeSpan.FromMilliseconds(650));
        FlashScale.BeginAnimation(ScaleTransform.ScaleXProperty, s);
        FlashScale.BeginAnimation(ScaleTransform.ScaleYProperty, s);
    }

    void Skip(long ms)
    {
        if (_mp == null || _req.IsLive || !_mp.IsSeekable) return;
        long len = _mp.Length;
        long t = Math.Clamp(_mp.Time + ms, 0, len > 0 ? len - 1500 : long.MaxValue);
        _mp.Time = t;
        if (len > 0) SetSeekVisual(t / (double)len);
        var flash = ms < 0 ? SeekBackFlash : SeekFwdFlash;
        flash.BeginAnimation(OpacityProperty, new DoubleAnimation(1, 0, TimeSpan.FromMilliseconds(700)) { BeginTime = TimeSpan.Zero, EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseIn } });
        Poke();
    }

    void PlayNext()
    {
        if (_next == null) return;
        SaveProgress();
        if (_mp != null && _mp.Length > 0 && _mp.Length - _mp.Time < 60000) Store.SetProgress(_req.Key, _mp.Length, _mp.Length, _req.ShowKey);
        Start(PlayRequest.ForEpisode(_next));
    }

    void ShowNextCard()
    {
        _nextShown = true;
        _nextShownAt = DateTime.Now;
        NextFill.Width = 0;
        NextCard.Visibility = Visibility.Visible;
    }

    void Zap(int dir)
    {
        var list = _req.Channels;
        if (list == null || list.Count < 2) return;
        int i = list.IndexOf(_req.Item);
        var next = list[((i < 0 ? 0 : i) + dir + list.Count) % list.Count];
        _retries = 0;
        Start(PlayRequest.ForChannel(next, list));
        Osd_Show(next.Name);
    }

    // ---------- Volume ----------

    int DesiredVolume => _muted ? 0 : Store.D.Volume;

    void ApplyVolume()
    {
        if (_mp == null) return;
        int v = _mp.Volume;
        if (v >= 0 && v != DesiredVolume) _mp.Volume = DesiredVolume;
    }

    void Vol(int delta)
    {
        _muted = false;
        Store.D.Volume = Math.Clamp(Store.D.Volume + delta, 0, 100);
        ApplyVolume();
        UpdateVolIcon();
        Osd_Show($"Volume {Store.D.Volume}%");
        Store.Save();
        Poke();
    }

    void ToggleMute()
    {
        _muted = !_muted;
        ApplyVolume();
        UpdateVolIcon();
        Osd_Show(_muted ? "Sem som" : $"Volume {Store.D.Volume}%");
    }

    void UpdateVolIcon()
    {
        bool silent = _muted || Store.D.Volume == 0;
        VolMute.Visibility = silent ? Visibility.Visible : Visibility.Collapsed;
        VolWaves.Visibility = silent ? Visibility.Collapsed : Visibility.Visible;
        double h = Math.Max(0, VolArea.ActualHeight > 0 ? VolArea.ActualHeight : 124);
        double f = _muted ? 0 : Store.D.Volume / 100.0;
        VolFill.Height = f * h;
        VolKnob.Margin = new Thickness(0, 0, 0, Math.Max(0, f * h - 7.5));
    }

    void Vol_Enter(object sender, MouseEventArgs e)
    {
        var p = VolBtn.TranslatePoint(new Point(0, 0), Ui);
        VolPop.Margin = new Thickness(p.X + (VolBtn.ActualWidth - VolPop.Width) / 2, 0, 0, Ui.ActualHeight - p.Y - 2);
        VolPop.Visibility = Visibility.Visible;
        Dispatcher.BeginInvoke(DispatcherPriority.Loaded, UpdateVolIcon);
    }

    void Vol_Down(object sender, MouseButtonEventArgs e) { _volDrag = true; VolArea.CaptureMouse(); VolAt(e.GetPosition(VolArea).Y); e.Handled = true; }
    void Vol_Move(object sender, MouseEventArgs e) { if (_volDrag) VolAt(e.GetPosition(VolArea).Y); }
    void Vol_Up(object sender, MouseButtonEventArgs e) { if (!_volDrag) return; _volDrag = false; VolArea.ReleaseMouseCapture(); Store.Save(); }

    void VolAt(double y)
    {
        double h = VolArea.ActualHeight;
        if (h <= 0) return;
        _muted = false;
        Store.D.Volume = (int)Math.Round(Math.Clamp(1 - y / h, 0, 1) * 100);
        ApplyVolume();
        UpdateVolIcon();
    }

    void Osd_Show(string text)
    {
        OsdText.Text = text;
        var a = new DoubleAnimationUsingKeyFrames();
        a.KeyFrames.Add(new LinearDoubleKeyFrame(1, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(80))));
        a.KeyFrames.Add(new LinearDoubleKeyFrame(1, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(1300))));
        a.KeyFrames.Add(new LinearDoubleKeyFrame(0, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(1700))));
        Osd.BeginAnimation(OpacityProperty, a);
    }

    // ---------- Barra de progresso ----------

    void SetSeekVisual(double f)
    {
        double w = SeekArea.ActualWidth;
        f = Math.Clamp(double.IsNaN(f) ? 0 : f, 0, 1);
        SeekFill.Width = f * w;
        SeekKnob.Margin = new Thickness(f * w - 8, 0, 0, 0);
    }

    void Seek_Down(object sender, MouseButtonEventArgs e)
    {
        if (_mp == null || _req.IsLive || _mp.Length <= 0) return;
        _seeking = true;
        SeekArea.CaptureMouse();
        SeekTo(e.GetPosition(SeekArea).X);
        e.Handled = true;
    }

    void Seek_Move(object sender, MouseEventArgs e)
    {
        double x = e.GetPosition(SeekArea).X;
        ShowTip(x);
        if (_seeking) SeekTo(x);
        Poke();
    }

    void Seek_Up(object sender, MouseButtonEventArgs e)
    {
        if (!_seeking) return;
        _seeking = false;
        SeekArea.ReleaseMouseCapture();
        if (_mp != null && _mp.Length > 0 && _mp.IsSeekable) _mp.Time = (long)(_seekFrac * _mp.Length);
        e.Handled = true;
    }

    void Seek_Enter(object sender, MouseEventArgs e)
    {
        SeekRail.Height = SeekFill.Height = SeekHover.Height = 6;
        KnobScale.ScaleX = KnobScale.ScaleY = 1.3;
    }

    void Seek_Leave(object sender, MouseEventArgs e)
    {
        if (_seeking) return;
        SeekRail.Height = SeekFill.Height = SeekHover.Height = 4;
        KnobScale.ScaleX = KnobScale.ScaleY = 1;
        SeekTip.Visibility = Visibility.Collapsed;
        SeekHover.Width = 0;
    }

    void SeekTo(double x)
    {
        double w = SeekArea.ActualWidth;
        if (w <= 0 || _mp == null) return;
        _seekFrac = Math.Clamp(x / w, 0, 1);
        SetSeekVisual(_seekFrac);
        Remaining.Text = UI.Time((long)((1 - _seekFrac) * _mp.Length));
    }

    void ShowTip(double x)
    {
        double w = SeekArea.ActualWidth;
        if (w <= 0 || _mp == null || _mp.Length <= 0) return;
        x = Math.Clamp(x, 0, w);
        SeekTipText.Text = UI.Time((long)(x / w * _mp.Length));
        SeekTip.Visibility = Visibility.Visible;
        SeekTip.UpdateLayout();
        Canvas.SetLeft(SeekTip, Math.Clamp(x - SeekTip.ActualWidth / 2, 0, Math.Max(0, w - SeekTip.ActualWidth)));
        SeekHover.Width = x;
    }

    // ---------- Painéis ----------

    void TogglePanel(string name, Func<UIElement> build)
    {
        if (_panel == name) { ClosePanel(); return; }
        _panel = name;
        PanelContent.Content = build();
        PanelBox.Visibility = Visibility.Visible;
        VolPop.Visibility = Visibility.Collapsed;
        ShowChrome();
    }

    void ClosePanel()
    {
        _panel = null;
        PanelBox.Visibility = Visibility.Collapsed;
        PanelContent.Content = null;
    }

    static TextBlock Header(string text) => new()
    {
        Text = text, FontSize = 22, FontWeight = FontWeights.Bold, Margin = new Thickness(0, 0, 0, 12),
    };

    Button Option(string text, bool selected, Action click, double fontSize = 17)
    {
        var check = new Viewbox { Width = 18, Height = 18, Margin = new Thickness(0, 0, 12, 0), Child = UI.Icon("G.Check", true, 3), Visibility = selected ? Visibility.Visible : Visibility.Hidden };
        var sp = new StackPanel { Orientation = Orientation.Horizontal };
        sp.Children.Add(check);
        sp.Children.Add(new TextBlock
        {
            Text = text, FontSize = fontSize, VerticalAlignment = VerticalAlignment.Center,
            Foreground = selected ? Brushes.White : new SolidColorBrush(Color.FromRgb(0xB3, 0xB3, 0xB3)),
            FontWeight = selected ? FontWeights.SemiBold : FontWeights.Normal, TextTrimming = TextTrimming.CharacterEllipsis,
        });
        var b = new Button { Style = (Style)FindResource("RowBtn"), Content = sp, Padding = new Thickness(10, 8, 16, 8), HorizontalContentAlignment = HorizontalAlignment.Left };
        b.Click += (_, _) => click();
        return b;
    }

    void Subs_Click(object sender, RoutedEventArgs e) => TogglePanel("subs", BuildSubs);

    UIElement BuildSubs()
    {
        var grid = new Grid { Margin = new Thickness(20, 22, 20, 18) };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Auto), MinWidth = 230 });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Auto), MinWidth = 250 });

        var audio = new StackPanel { Margin = new Thickness(0, 0, 24, 0) };
        audio.Children.Add(Header("Áudio"));
        var tracks = SafeTracks(() => _mp.AudioTrackDescription).Where(t => t.Id != -1).ToList();
        int curA = _mp.AudioTrack;
        if (tracks.Count == 0) audio.Children.Add(new TextBlock { Text = "Padrão", FontSize = 16, Foreground = Brushes.Gray, Margin = new Thickness(40, 6, 0, 0) });
        int n = 1;
        foreach (var t in tracks)
        {
            int id = t.Id;
            audio.Children.Add(Option(TrackName(t.Name, n++, false), id == curA, () => { _mp.SetAudioTrack(id); Rebuild("subs", BuildSubs); }));
        }
        grid.Children.Add(audio);

        var subs = new StackPanel();
        subs.Children.Add(Header("Legendas"));
        var spu = SafeTracks(() => _mp.SpuDescription).ToList();
        int curS = _mp.Spu;
        subs.Children.Add(Option("Desativadas", curS == -1, () => { _mp.SetSpu(-1); Rebuild("subs", BuildSubs); }));
        n = 1;
        foreach (var t in spu.Where(t => t.Id != -1))
        {
            int id = t.Id;
            subs.Children.Add(Option(TrackName(t.Name, n++, true), id == curS, () => { _mp.SetSpu(id); Rebuild("subs", BuildSubs); }));
        }
        var load = new Button { Style = (Style)FindResource("LinkBtn"), Content = "+ Carregar arquivo de legenda...", Margin = new Thickness(10, 12, 0, 0), HorizontalAlignment = HorizontalAlignment.Left, FontSize = 14 };
        load.Click += (_, _) => LoadSubtitle();
        subs.Children.Add(load);
        Grid.SetColumn(subs, 1);
        grid.Children.Add(subs);
        return grid;
    }

    static TrackDescription[] SafeTracks(Func<TrackDescription[]> get)
    {
        try { return get() ?? Array.Empty<TrackDescription>(); } catch { return Array.Empty<TrackDescription>(); }
    }

    void Rebuild(string name, Func<UIElement> build)
    {
        Dispatcher.BeginInvoke(DispatcherPriority.Background, () => { if (_panel == name) PanelContent.Content = build(); });
    }

    void LoadSubtitle()
    {
        var dlg = new Microsoft.Win32.OpenFileDialog { Title = "Escolha a legenda", Filter = "Legendas (*.srt;*.ass;*.ssa;*.vtt;*.sub)|*.srt;*.ass;*.ssa;*.vtt;*.sub|Todos os arquivos|*.*" };
        bool wasFull = MainWindow.I.IsFullscreen;
        if (dlg.ShowDialog(Window.GetWindow(this)) != true) return;
        _mp.AddSlave(MediaSlaveType.Subtitle, new Uri(dlg.FileName).AbsoluteUri, true);
        Osd_Show("Legenda carregada");
        ClosePanel();
        if (wasFull && !MainWindow.I.IsFullscreen) MainWindow.I.SetFullscreen(true);
    }

    static readonly Dictionary<string, string> Langs = new(StringComparer.OrdinalIgnoreCase)
    {
        ["english"] = "Inglês", ["eng"] = "Inglês", ["en"] = "Inglês", ["portuguese"] = "Português", ["por"] = "Português", ["pt"] = "Português",
        ["pt-br"] = "Português (Brasil)", ["brazilian portuguese"] = "Português (Brasil)", ["spanish"] = "Espanhol", ["spa"] = "Espanhol", ["es"] = "Espanhol",
        ["french"] = "Francês", ["fre"] = "Francês", ["fra"] = "Francês", ["german"] = "Alemão", ["ger"] = "Alemão", ["deu"] = "Alemão",
        ["italian"] = "Italiano", ["ita"] = "Italiano", ["japanese"] = "Japonês", ["jpn"] = "Japonês", ["korean"] = "Coreano", ["kor"] = "Coreano",
        ["chinese"] = "Chinês", ["chi"] = "Chinês", ["zho"] = "Chinês", ["russian"] = "Russo", ["rus"] = "Russo", ["undetermined"] = "Indefinido", ["und"] = "Indefinido",
    };

    static string TrackName(string raw, int index, bool sub)
    {
        raw ??= "";
        var m = Regex.Match(raw, @"\[(.+?)\]");
        string lang = m.Success ? m.Groups[1].Value.Trim() : null;
        var rest = Regex.Replace(m.Success ? raw.Replace(m.Value, "") : raw, @"^\s*(Track|Faixa|Pista)\s*\d+\s*", "", RegexOptions.IgnoreCase).Trim(' ', '-', '–');
        if (lang != null && Langs.TryGetValue(lang, out var pt)) lang = pt;
        else if (lang != null && lang.Length > 0) lang = char.ToUpper(lang[0]) + lang[1..];
        if (rest.Length > 0 && Langs.TryGetValue(rest, out var pt2)) rest = pt2;
        if (lang != null) return rest.Length > 0 ? $"{lang} ({rest})" : lang;
        return rest.Length > 0 ? rest : (sub ? $"Legenda {index}" : $"Faixa {index}");
    }

    void Speed_Click(object sender, RoutedEventArgs e) => TogglePanel("speed", BuildSpeed);

    UIElement BuildSpeed()
    {
        var root = new StackPanel { Margin = new Thickness(28, 24, 28, 22), MinWidth = 520 };
        root.Children.Add(Header("Velocidade de reprodução"));
        var rates = new[] { 0.5f, 0.75f, 1f, 1.25f, 1.5f };
        float cur = _mp.Rate;
        var line = new Grid { Margin = new Thickness(0, 6, 0, 0) };
        line.Children.Add(new Border { Height = 2, Background = new SolidColorBrush(Color.FromRgb(0x5A, 0x5A, 0x5A)), VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(48, 15, 48, 0) });
        var row = new UniformGrid { Columns = rates.Length };
        foreach (var r in rates)
        {
            bool sel = Math.Abs(cur - r) < 0.01;
            var sp = new StackPanel();
            var dotHost = new Grid { Height = 32 };
            dotHost.Children.Add(sel
                ? new Ellipse { Width = 30, Height = 30, Stroke = Brushes.White, StrokeThickness = 3, Fill = new SolidColorBrush(Color.FromRgb(0x26, 0x26, 0x26)) }
                : new Ellipse { Width = 14, Height = 14, Fill = new SolidColorBrush(Color.FromRgb(0xB3, 0xB3, 0xB3)) });
            if (sel) dotHost.Children.Add(new Ellipse { Width = 14, Height = 14, Fill = Brushes.White });
            sp.Children.Add(dotHost);
            sp.Children.Add(new TextBlock
            {
                Text = r == 1f ? "1x (Normal)" : r.ToString("0.##", Inv).Replace('.', ',') + "x", FontSize = 16, HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 10, 0, 0), FontWeight = sel ? FontWeights.SemiBold : FontWeights.Normal,
                Foreground = sel ? Brushes.White : new SolidColorBrush(Color.FromRgb(0xB3, 0xB3, 0xB3)),
            });
            var b = new Button { Style = (Style)FindResource("Plain"), Content = sp, Padding = new Thickness(4) };
            float rate = r;
            b.Click += (_, _) => { _mp.SetRate(rate); Osd_Show(rate == 1f ? "Velocidade normal" : $"Velocidade {rate.ToString("0.##", Inv).Replace('.', ',')}x"); Rebuild("speed", BuildSpeed); };
            row.Children.Add(b);
        }
        line.Children.Add(row);
        root.Children.Add(line);

        var title = Header("Imagem");
        title.Margin = new Thickness(0, 26, 0, 12);
        root.Children.Add(title);
        var chips = new WrapPanel();
        foreach (var (key, label) in new[] { ("original", "Original"), ("fill", "Preencher a tela"), ("16:9", "16:9"), ("4:3", "4:3") })
        {
            var rb = new RadioButton { Style = (Style)FindResource("Chip"), Content = label, GroupName = "aspect" + GetHashCode(), IsChecked = _aspect == key };
            string k = key;
            rb.Checked += (_, _) => { _aspect = k; ApplyAspect(); };
            chips.Children.Add(rb);
        }
        root.Children.Add(chips);
        return root;
    }

    void ApplyAspect()
    {
        if (_mp == null) return;
        switch (_aspect)
        {
            case "fill":
                _mp.AspectRatio = null;
                var w = (int)Math.Max(1, Video.ActualWidth);
                var h = (int)Math.Max(1, Video.ActualHeight);
                _mp.CropGeometry = $"{w}:{h}";
                break;
            case "16:9":
            case "4:3":
                _mp.CropGeometry = null;
                _mp.AspectRatio = _aspect;
                break;
            default:
                _mp.CropGeometry = null;
                _mp.AspectRatio = null;
                break;
        }
    }

    void Episodes_Click(object sender, RoutedEventArgs e) => TogglePanel("episodes", () => BuildEpisodes(_req.Episode.Season));

    UIElement BuildEpisodes(int season)
    {
        var show = _req.Item;
        var root = new StackPanel { Margin = new Thickness(20, 22, 20, 18), Width = 520 };
        root.Children.Add(Header(show.Name));
        var seasons = show.Seasons;
        if (seasons.Count > 1)
        {
            var chips = new WrapPanel { Margin = new Thickness(0, 0, 0, 6) };
            foreach (var s in seasons)
            {
                var rb = new RadioButton { Style = (Style)FindResource("Chip"), Content = $"Temporada {s}", GroupName = "pseason" + GetHashCode(), IsChecked = s == season };
                int ss = s;
                rb.Checked += (_, _) => Dispatcher.BeginInvoke(DispatcherPriority.Background, () => { if (_panel == "episodes") PanelContent.Content = BuildEpisodes(ss); });
                chips.Children.Add(rb);
            }
            root.Children.Add(chips);
        }
        var list = new StackPanel();
        Button currentBtn = null;
        foreach (var ep in show.Episodes.Where(x => x.Season == season))
        {
            bool cur = ep == _req.Episode;
            var p = Store.GetProgress(ep.Key);
            var g = new Grid();
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(40) });
            g.ColumnDefinitions.Add(new ColumnDefinition());
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.Children.Add(new TextBlock { Text = ep.Number.ToString(), FontSize = 17, Foreground = Brushes.Gray, VerticalAlignment = VerticalAlignment.Center });
            var title = new TextBlock { Text = ep.Title, FontSize = 16, FontWeight = cur ? FontWeights.Bold : FontWeights.Normal, TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center };
            Grid.SetColumn(title, 1);
            g.Children.Add(title);
            if (p != null)
            {
                var bar = new Grid { Width = 70, Height = 3, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(12, 0, 0, 0) };
                bar.Children.Add(new Border { Background = new SolidColorBrush(Color.FromRgb(0x5A, 0x5A, 0x5A)) });
                bar.Children.Add(new Border { Background = (Brush)FindResource("Red"), HorizontalAlignment = HorizontalAlignment.Left, Width = 70 * (p.Finished ? 1 : p.Fraction) });
                Grid.SetColumn(bar, 2);
                g.Children.Add(bar);
            }
            var b = new Button
            {
                Style = (Style)FindResource("RowBtn"), Content = g, Padding = new Thickness(12, 11, 12, 11),
                Background = cur ? new SolidColorBrush(Color.FromArgb(0x33, 0xFF, 0xFF, 0xFF)) : Brushes.Transparent,
            };
            var target = ep;
            b.Click += (_, _) => { if (target != _req.Episode) Switch(PlayRequest.ForEpisode(target)); else ClosePanel(); };
            list.Children.Add(b);
            if (cur) currentBtn = b;
        }
        var sv = new ScrollViewer { Content = list, MaxHeight = Math.Max(200, Ui.ActualHeight - 340), VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        Smooth.SetOn(sv, true);
        root.Children.Add(sv);
        if (currentBtn != null) Dispatcher.BeginInvoke(DispatcherPriority.Loaded, () => currentBtn.BringIntoView());
        return root;
    }

    void Channels_Click(object sender, RoutedEventArgs e) => TogglePanel("channels", BuildChannels);

    UIElement BuildChannels()
    {
        var root = new StackPanel { Margin = new Thickness(16, 20, 16, 16), Width = 400 };
        var h = Header("Canais");
        h.Margin = new Thickness(6, 0, 0, 12);
        root.Children.Add(h);
        var list = new VirtualizingStackPanel();
        Button currentBtn = null;
        foreach (var ch in _req.Channels)
        {
            bool cur = ch == _req.Item;
            var g = new Grid();
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(64) });
            g.ColumnDefinitions.Add(new ColumnDefinition());
            var logo = new Image { Width = 52, Height = 32, Stretch = Stretch.Uniform };
            Img.SetDecode(logo, 104);
            Img.SetSrc(logo, ch.Logo);
            g.Children.Add(logo);
            var name = new TextBlock { Text = ch.Name, FontSize = 15, FontWeight = cur ? FontWeights.Bold : FontWeights.Normal, VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis };
            Grid.SetColumn(name, 1);
            g.Children.Add(name);
            var b = new Button
            {
                Style = (Style)FindResource("RowBtn"), Content = g, Padding = new Thickness(6, 7, 10, 7),
                Background = cur ? new SolidColorBrush(Color.FromArgb(0x33, 0xFF, 0xFF, 0xFF)) : Brushes.Transparent,
            };
            var target = ch;
            b.Click += (_, _) => { if (target != _req.Item) { _retries = 0; Switch(PlayRequest.ForChannel(target, _req.Channels)); } else ClosePanel(); };
            list.Children.Add(b);
            if (cur) currentBtn = b;
        }
        var sv = new ScrollViewer { Content = list, MaxHeight = Math.Max(220, Ui.ActualHeight - 300), VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        Smooth.SetOn(sv, true);
        root.Children.Add(sv);
        if (currentBtn != null) Dispatcher.BeginInvoke(DispatcherPriority.Loaded, () => currentBtn.BringIntoView());
        return root;
    }

    // ---------- Botões ----------

    void Play_Click(object sender, RoutedEventArgs e) => TogglePlay();
    void Rew_Click(object sender, RoutedEventArgs e) => Skip(-10000);
    void Fwd_Click(object sender, RoutedEventArgs e) => Skip(10000);
    void Mute_Click(object sender, RoutedEventArgs e) => ToggleMute();
    void Next_Click(object sender, RoutedEventArgs e) => PlayNext();
    void ChPrev_Click(object sender, RoutedEventArgs e) => Zap(-1);
    void ChNext_Click(object sender, RoutedEventArgs e) => Zap(1);
    void Full_Click(object sender, RoutedEventArgs e) => ToggleFullscreen();
    void Back_Click(object sender, RoutedEventArgs e) => Close();

    void Credits_Click(object sender, RoutedEventArgs e)
    {
        _creditsDismissed = true;
        _nextShown = false;
        NextCard.Visibility = Visibility.Collapsed;
    }

    void StartOver_Click(object sender, RoutedEventArgs e)
    {
        if (_mp == null) return;
        _mp.Time = 0;
        ResumeToast.Visibility = Visibility.Collapsed;
        Poke();
    }

    void ToggleFullscreen()
    {
        MainWindow.I.SetFullscreen(!MainWindow.I.IsFullscreen);
        Poke();
    }

    // ---------- Teclado ----------

    public bool HandleKey(Key k)
    {
        if (_closing) return false;
        bool live = _req.IsLive;
        switch (k)
        {
            case Key.Space: case Key.K: case Key.MediaPlayPause: case Key.Play: case Key.Pause: TogglePlay(); return true;
            case Key.Left: case Key.J: if (!live) Skip(-10000); return true;
            case Key.Right: case Key.L: if (!live) Skip(10000); return true;
            case Key.Up: Vol(5); return true;
            case Key.Down: Vol(-5); return true;
            case Key.M: case Key.VolumeMute: ToggleMute(); return true;
            case Key.F: case Key.F11: ToggleFullscreen(); return true;
            case Key.Enter: if (_nextShown) { PlayNext(); return true; } return false;
            case Key.N: case Key.MediaNextTrack: if (live) Zap(1); else PlayNext(); return true;
            case Key.PageDown: Zap(1); return true;
            case Key.PageUp: case Key.MediaPreviousTrack: Zap(-1); return true;
            case Key.Back: case Key.BrowserBack: case Key.MediaStop: Close(); return true;
            case Key.Escape:
                if (_panel != null) ClosePanel();
                else if (MainWindow.I.IsFullscreen) MainWindow.I.SetFullscreen(false);
                else Close();
                return true;
        }
        return false;
    }

    // ---------- Encerrar ----------

    public void Close()
    {
        if (_closing) return;
        SaveProgress();
        _closing = true;
        _tick.Stop();
        _clickTimer.Stop();
        Ui.Cursor = null;
        Ui.Visibility = Visibility.Collapsed;
        Video.Visibility = Visibility.Hidden;
        MainWindow.I.PlayerLeaving(this);

        var mp = _mp;
        var media = _media;
        _mp = null;
        _media = null;
        if (mp == null) { MainWindow.I.PlayerGone(this); return; }
        Task.Run(() => { try { mp.Stop(); } catch { } }).ContinueWith(_ => Dispatcher.BeginInvoke(() =>
        {
            try { Video.MediaPlayer = null; } catch { }
            try { mp.Dispose(); } catch { }
            try { media?.Dispose(); } catch { }
            try { Video.Dispose(); } catch { }
            MainWindow.I.PlayerGone(this);
        }));
    }

    /// <summary>App fechando: salva o progresso e para o VLC na hora.</summary>
    public void Shutdown()
    {
        if (_closing) return;
        SaveProgress();
        _closing = true;
        _tick.Stop();
        try { _mp?.Stop(); } catch { }
    }
}
