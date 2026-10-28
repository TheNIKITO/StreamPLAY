using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Threading;

namespace StreamPLAY;

public class RowVM
{
    public string Title { get; init; }
    public Kind Kind { get; init; }
    public List<Card> Cards { get; init; }
    public int Total { get; init; }
    public bool CanShowAll => Total > 6;
    public IList<Item> All { get; init; }
    public bool Dynamic { get; init; }
    public bool Continue { get; init; }   // "Continuar assistindo": cartão com nome, episódio e progresso embaixo
}

public class GridRowVM
{
    public List<Card> Cards { get; init; }
    public int Cols { get; init; }
}

public class SpacerVM { public double Height { get; init; } }
public class MessageVM { public string Title { get; init; } public string Text { get; init; } }

public class HeroVM : INotifyPropertyChanged
{
    Item _item;
    double _height = 640;
    public Item Item { get => _item; set { _item = value; On(nameof(Item)); On(nameof(NoBackdrop)); On(nameof(PlayText)); On(nameof(TitleA)); On(nameof(TitleB)); On(nameof(KindText)); } }
    public bool NoBackdrop => _item != null && !_item.HasBackdrop;
    // título com a última palavra em vermelho ("This Is Going to" + "Hurt"); com uma palavra só, fica todo branco
    public string TitleA => Split().a;
    public string TitleB => Split().b;
    (string a, string b) Split()
    {
        var n = _item?.Name?.Trim() ?? "";
        int i = n.LastIndexOf(' ');
        return i <= 0 ? (n, "") : (n[..(i + 1)], n[(i + 1)..]);
    }
    public string KindText => _item?.Kind == Kind.Series ? "S É R I E" : "F I L M E";   // espaçado (o WPF não tem espaçamento entre letras)
    public double Height { get => _height; set { _height = value; On(nameof(Height)); } }
    public string PlayText
    {
        get
        {
            if (_item == null) return "Assistir";
            if (_item.Kind == Kind.Movie) return Store.Watched(_item.Key) > 0 ? "Continuar" : "Assistir";
            var ep = Resume.EpisodeFor(_item);
            return ep != null && (Store.GetProgress(ep.Key) != null || ep != _item.Episodes.FirstOrDefault()) ? $"Continuar {ep.Label}" : "Assistir";
        }
    }
    public void RefreshText() => On(nameof(PlayText));
    void On(string p) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(p));
    public event PropertyChangedEventHandler PropertyChanged;
}

/// <summary>Página de Filmes, Séries, Início (home: filmes e séries juntos) ou Minha lista (kind = null).</summary>
public partial class BrowseView : UserControl, IPage
{
    readonly Library _lib;
    readonly Kind? _kind;
    readonly bool _home;
    bool TemFeed => _kind != null || _home;
    readonly ObservableCollection<object> _feed = new();
    HeroVM _hero;
    List<Item> _heroPool = new();
    int _heroIndex;
    readonly DispatcherTimer _heroTimer = new() { Interval = TimeSpan.FromSeconds(11) };
    readonly DispatcherTimer _resizeTimer = new() { Interval = TimeSpan.FromMilliseconds(120) };
    ScrollViewer _sv;

    // modo grade (categoria / busca / minha lista)
    string _gridTitle;
    IList<Item> _gridItems;
    bool _searching;
    int _cols;

    public BrowseView(Library lib, Kind? kind, bool home = false)
    {
        InitializeComponent();
        _lib = lib;
        _kind = kind;
        _home = home;
        Feed.ItemsSource = _feed;
        PageTitle.Text = Titulo;
        AddHandler(ButtonBase.ClickEvent, new RoutedEventHandler(OnClick));
        CatList.AddHandler(ButtonBase.ClickEvent, new RoutedEventHandler(OnClick));
        _heroTimer.Tick += (_, _) => NextHero();
        _resizeTimer.Tick += (_, _) => { _resizeTimer.Stop(); OnResized(); };
        SizeChanged += (_, _) => { if (_hero != null) _hero.Height = HeroHeight(); _resizeTimer.Stop(); _resizeTimer.Start(); };
        Loaded += (_, _) =>
        {
            _sv ??= UI.Find<ScrollViewer>(Feed);
            if (_sv != null) { _sv.ScrollChanged -= OnScroll; _sv.ScrollChanged += OnScroll; }
            if (_gridItems == null && TemFeed) _heroTimer.Start();
        };
        Unloaded += (_, _) => _heroTimer.Stop();

        if (!TemFeed) ShowGrid("Minha lista", Favorites(), isRoot: true);
        else BuildFeed();
    }

    string Titulo => _home ? "" : _kind == Kind.Movie ? "Filmes" : _kind == Kind.Series ? "Séries" : "Minha lista";

    // o banner deixa a primeira fileira (Continuar assistindo) aparecer sem rolar
    double HeroHeight() => Math.Clamp(Math.Min(ActualWidth * 0.42, ActualHeight * 0.72), 440, 760);

    // ---------- Feed (hero + fileiras) ----------

    void BuildFeed()
    {
        _gridItems = null;
        _searching = false;
        BackBtn.Visibility = Visibility.Collapsed;
        PageCount.Text = "";
        if (_home) { BuildHome(); return; }
        var kind = _kind.Value;
        var cats = _lib.Cats(kind).Where(c => c.Items.Count > 0).ToList();
        CatBtn.Visibility = cats.Count > 1 ? Visibility.Visible : Visibility.Collapsed;
        CatList.ItemsSource = cats;
        int cols = cats.Count > 24 ? 3 : cats.Count > 10 ? 2 : 1;
        var panel = new FrameworkElementFactory(typeof(UniformGrid));
        panel.SetValue(UniformGrid.ColumnsProperty, cols);
        CatList.ItemsPanel = new ItemsPanelTemplate(panel);

        _feed.Clear();
        if (cats.Count == 0)
        {
            _feed.Add(new SpacerVM { Height = 150 });
            _feed.Add(new MessageVM
            {
                Title = kind == Kind.Movie ? "Nenhum filme na sua lista ainda" : "Nenhuma série na sua lista ainda",
                Text = kind == Kind.Movie
                    ? "Adicione filmes no seu arquivo .m3u com group-title=\"FILMES\" (ou \"FILMES | Lançamentos\") e salve — o StreamPLAY atualiza sozinho."
                    : "Adicione episódios no seu arquivo .m3u com group-title=\"SERIES\" e o nome no formato \"Nome da Série S01E01\". Salve e o StreamPLAY organiza por temporada sozinho.",
            });
            _heroTimer.Stop();
            return;
        }

        var release = cats.Where(c => c.IsRelease).SelectMany(c => c.Items).Distinct().ToList();
        _heroPool = (release.Count > 0 ? release : _lib.All(kind)).Where(i => !string.IsNullOrEmpty(i.Logo) || i.HasBackdrop).Take(15).ToList();
        if (_heroPool.Count == 0) _heroPool = _lib.All(kind).Take(15).ToList();
        _heroIndex = Random.Shared.Next(_heroPool.Count);
        _hero = new HeroVM { Item = _heroPool[_heroIndex], Height = ActualHeight > 0 ? HeroHeight() : 640 };
        _feed.Add(_hero);

        foreach (var row in DynamicRows()) _feed.Add(row);
        foreach (var c in cats)
            _feed.Add(new RowVM
            {
                Title = c.Name, Kind = kind, Total = c.Items.Count, All = c.Items,
                Cards = c.Items.Take(40).Select(i => MakeCard(i, inRelease: c.IsRelease)).ToList(),
            });
        if (IsLoaded) _heroTimer.Start();
    }

    // Início: lançamentos de filmes e séries no banner, depois continuar assistindo, minha lista,
    // lançamentos e as primeiras categorias de filmes e de séries alternadas
    void BuildHome()
    {
        CatBtn.Visibility = Visibility.Collapsed;
        PageTitle.Text = "";
        var filmes = _lib.Cats(Kind.Movie).Where(c => c.Items.Count > 0).ToList();
        var series = _lib.Cats(Kind.Series).Where(c => c.Items.Count > 0).ToList();
        _feed.Clear();
        if (filmes.Count == 0 && series.Count == 0)
        {
            _feed.Add(new SpacerVM { Height = 150 });
            _feed.Add(new MessageVM
            {
                Title = "Nenhum filme ou série na sua lista ainda",
                Text = "Os filmes e séries do seu arquivo .m3u aparecem aqui. Os canais ficam na aba Canais.",
            });
            _heroTimer.Stop();
            return;
        }

        var release = filmes.Concat(series).Where(c => c.IsRelease).SelectMany(c => c.Items).Distinct().ToList();
        var todos = release.Count > 0 ? release : _lib.AllMovies.Concat(_lib.AllSeries).ToList();
        _heroPool = todos.Where(i => !string.IsNullOrEmpty(i.Logo) || i.HasBackdrop).Take(15).ToList();
        if (_heroPool.Count == 0) _heroPool = todos.Take(15).ToList();
        _heroIndex = Random.Shared.Next(_heroPool.Count);
        _hero = new HeroVM { Item = _heroPool[_heroIndex], Height = ActualHeight > 0 ? HeroHeight() : 640 };
        _feed.Add(_hero);

        foreach (var row in DynamicRows()) _feed.Add(row);
        RowVM Linha(Category c, Kind k) => new()
        {
            Title = (k == Kind.Movie ? "Filmes" : "Séries") + (c.IsRelease ? " em lançamento" : " • " + c.Name),
            Kind = k, Total = c.Items.Count, All = c.Items,
            Cards = c.Items.Take(40).Select(i => MakeCard(i, inRelease: c.IsRelease)).ToList(),
        };
        foreach (var c in filmes.Where(c => c.IsRelease)) _feed.Add(Linha(c, Kind.Movie));
        foreach (var c in series.Where(c => c.IsRelease)) _feed.Add(Linha(c, Kind.Series));
        // o resto fica nas abas Filmes e Séries: aqui só as primeiras categorias, para a página não ficar enorme
        var f = filmes.Where(c => !c.IsRelease).Take(6).ToList();
        var s = series.Where(c => !c.IsRelease).Take(6).ToList();
        for (int i = 0; i < Math.Max(f.Count, s.Count); i++)
        {
            if (i < f.Count) _feed.Add(Linha(f[i], Kind.Movie));
            if (i < s.Count) _feed.Add(Linha(s[i], Kind.Series));
        }
        if (IsLoaded) _heroTimer.Start();
    }

    IEnumerable<RowVM> DynamicRows()
    {
        Kind? kind = _home ? null : _kind;
        var cont = Resume.ContinueCards(_lib, kind);
        if (cont.Count > 0)
            yield return new RowVM { Title = "Continuar assistindo", Kind = kind ?? Kind.Movie, Cards = cont, Total = cont.Count, Dynamic = true, Continue = true };
        var favs = Favorites().Where(i => kind == null || i.Kind == kind).ToList();
        if (favs.Count > 0)
            yield return new RowVM { Title = "Minha lista", Kind = kind ?? Kind.Movie, Cards = favs.Take(40).Select(i => MakeCard(i)).ToList(), Total = favs.Count, All = favs, Dynamic = true };
    }

    Card MakeCard(Item i, bool inRelease = false) => new(i)
    {
        Sub = i.Meta,
        IsNew = i.IsRelease && !inRelease,
        Progress = i.Kind == Kind.Movie ? Store.Watched(i.Key) : 0,
    };

    List<Item> Favorites() => Store.D.Favorites.Select(_lib.Get).Where(i => i != null && i.Kind != Kind.Channel).ToList();

    public void Refresh()
    {
        if (!TemFeed) { ShowGrid("Minha lista", Favorites(), isRoot: true, keepScroll: true); return; }
        if (_gridItems != null) { ShowGrid(_gridTitle, _gridItems, keepScroll: true); return; }
        if (_hero == null) { BuildFeed(); return; }
        // troca só as fileiras dinâmicas (continuar assistindo / minha lista)
        for (int i = _feed.Count - 1; i >= 0; i--) if (_feed[i] is RowVM { Dynamic: true }) _feed.RemoveAt(i);
        int at = 1;
        foreach (var row in DynamicRows()) _feed.Insert(at++, row);
        _hero.RefreshText();
    }

    void NextHero()
    {
        // com o player aberto o banner fica parado (a tela de trás não precisa animar enquanto o vídeo toca)
        if (_hero == null || _heroPool.Count < 2 || _gridItems != null || !IsVisible || MainWindow.I.TocandoVideo) return;
        if (_sv != null && _sv.VerticalOffset > _hero.Height * 0.5) return;
        if (IsMouseOverHero()) return;
        var container = Feed.ItemContainerGenerator.ContainerFromItem(_hero) as UIElement;
        _heroIndex = (_heroIndex + 1) % _heroPool.Count;
        var next = _heroPool[_heroIndex];
        if (container == null) { _hero.Item = next; return; }
        UI.Fade(container, 0, 350, () => { _hero.Item = next; UI.Fade(container, 1, 450); });
    }

    bool IsMouseOverHero() => Feed.ItemContainerGenerator.ContainerFromItem(_hero) is UIElement c && c.IsMouseOver &&
                              System.Windows.Input.Mouse.GetPosition(c).Y < _hero.Height - 130;

    // ---------- Grade ----------

    void ShowGrid(string title, IList<Item> items, bool isRoot = false, bool keepScroll = false)
    {
        double off = keepScroll && _sv != null ? _sv.VerticalOffset : 0;
        _gridTitle = title;
        _gridItems = items;
        _heroTimer.Stop();
        PageTitle.Text = title;
        PageCount.Text = items.Count > 0 ? $"{items.Count} {(items.Count == 1 ? "título" : "títulos")}" : "";
        BackBtn.Visibility = isRoot ? Visibility.Collapsed : Visibility.Visible;
        CatBtn.Visibility = Visibility.Collapsed;
        _cols = Cols();
        _feed.Clear();
        _feed.Add(new SpacerVM { Height = 140 });
        if (items.Count == 0)
        {
            _feed.Add(_searching
                ? new MessageVM { Title = $"Nada encontrado para \"{title.Replace("Resultados para ", "").Trim('"')}\"", Text = "Confira a ortografia ou tente outro nome." }
                : _kind == null
                    ? new MessageVM { Title = "Sua lista está vazia", Text = "Abra um filme ou série e clique no botão + para guardar aqui." }
                    : new MessageVM { Title = "Nada por aqui", Text = "" });
        }
        foreach (var row in UI.Rows(items.Select(i => MakeCard(i)), _cols)) _feed.Add(new GridRowVM { Cards = row, Cols = _cols });
        if (_sv != null) Dispatcher.BeginInvoke(DispatcherPriority.Loaded, () => _sv.ScrollToVerticalOffset(off));
        if (!keepScroll) Smooth.Reset(_sv);
    }

    int Cols() => Math.Max(2, (int)((Math.Max(ActualWidth, 900) - 104 + 8) / (178 + 8)));

    void ExitGrid()
    {
        if (!TemFeed) return;
        PageTitle.Text = Titulo;
        BuildFeed();
        Smooth.Reset(_sv);
    }

    void OnResized()
    {
        if (_gridItems == null || Cols() == _cols) return;
        ShowGrid(_gridTitle, _gridItems, isRoot: !TemFeed, keepScroll: true);
    }

    public void Search(string text)
    {
        if (string.IsNullOrEmpty(text))
        {
            if (!_searching) return;
            _searching = false;
            if (!TemFeed) ShowGrid("Minha lista", Favorites(), isRoot: true); else ExitGrid();
            return;
        }
        _searching = true;
        var q = M3U.Norm(text);
        IEnumerable<Item> pool = _kind == null ? _lib.AllMovies.Concat(_lib.AllSeries) : _lib.All(_kind.Value);
        var found = pool.Where(i => M3U.Norm(i.Name).Contains(q))
                        .OrderBy(i => M3U.Norm(i.Name).StartsWith(q) ? 0 : 1).ToList();
        ShowGrid($"Resultados para \"{text}\"", found);
    }

    // ---------- Cliques ----------

    void OnClick(object sender, RoutedEventArgs e)
    {
        if (e.OriginalSource is not FrameworkElement fe || fe.Tag is not string tag) return;
        e.Handled = true;
        switch (tag)
        {
            case "open" when fe.DataContext is Card c:
                if (c.Ep != null) MainWindow.I.Play(PlayRequest.ForEpisode(c.Ep));
                else if (c.Direct) MainWindow.I.Play(PlayRequest.ForItem(c.Item));
                else MainWindow.I.ShowDetails(c.Item);
                break;
            case "hero-play" when fe.DataContext is HeroVM h:
                MainWindow.I.Play(PlayRequest.ForItem(h.Item));
                break;
            case "hero-info" when fe.DataContext is HeroVM h:
                MainWindow.I.ShowDetails(h.Item);
                break;
            case "all" when fe.DataContext is RowVM r && r.All != null:
                ShowGrid(r.Title, r.All);
                break;
            case "cat" when fe.DataContext is Category cat:
                CatPopup.IsOpen = false;
                ShowGrid(cat.Name, cat.Items);
                break;
            case "back":
                if (_searching) MainWindow.I.Navigate(_home ? "home" : _kind == Kind.Series ? "series" : _kind == Kind.Movie ? "movies" : "mylist");
                else ExitGrid();
                break;
            default:
                e.Handled = false;
                break;
        }
    }

    void CatBtn_Click(object sender, RoutedEventArgs e) => CatPopup.IsOpen = !CatPopup.IsOpen;

    void OnScroll(object sender, ScrollChangedEventArgs e)
    {
        var v = Math.Clamp(e.VerticalOffset / 90.0, 0, 1);
        SubBg.Opacity = v;
        MainWindow.I.SetTopSolid(v);
    }
}
