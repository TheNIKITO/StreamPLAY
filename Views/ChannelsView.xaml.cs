using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Threading;

namespace StreamPLAY;

public class CatVM
{
    public string Key { get; init; }
    public string Name { get; init; }
    public int Count { get; init; }
    public IList<Item> Items { get; init; }
}

public partial class ChannelsView : UserControl, IPage
{
    readonly Library _lib;
    List<Item> _current = new();
    string _search;
    int _cols;
    readonly DispatcherTimer _resizeTimer = new() { Interval = TimeSpan.FromMilliseconds(120) };
    // guia de programação: os cartões na tela e o relógio que atualiza o "Agora" deles
    List<Card> _cards = new();
    readonly DispatcherTimer _guiaTimer = new() { Interval = TimeSpan.FromSeconds(30) };

    public ChannelsView(Library lib)
    {
        InitializeComponent();
        _lib = lib;
        Total.Text = lib.AllChannels.Count == 1 ? "1 canal" : $"{lib.AllChannels.Count} canais";
        AddHandler(ButtonBase.ClickEvent, new RoutedEventHandler(OnClick));
        _resizeTimer.Tick += (_, _) => { _resizeTimer.Stop(); if (Cols() != _cols) Show(keepScroll: true); };
        ChGrid.SizeChanged += (_, _) => { _resizeTimer.Stop(); _resizeTimer.Start(); };
        _guiaTimer.Tick += (_, _) => AtualizarGuia();
        Loaded += (_, _) => { Guia.Mudou += AtualizarGuia; _guiaTimer.Start(); AtualizarGuia(); };
        Unloaded += (_, _) => { Guia.Mudou -= AtualizarGuia; _guiaTimer.Stop(); };
        BuildCats(null);
    }

    void AtualizarGuia()
    {
        var agora = DateTime.Now;
        foreach (var c in _cards) c.AtualizarGuia(agora);
    }

    void BuildCats(string selectKey)
    {
        var list = new List<CatVM>();
        var favs = Store.D.Favorites.Select(_lib.Get).Where(i => i?.Kind == Kind.Channel).ToList();
        var recent = Store.D.Recent.Select(_lib.Get).Where(i => i?.Kind == Kind.Channel).ToList();
        if (favs.Count > 0) list.Add(new CatVM { Key = "fav", Name = "★  Favoritos", Count = favs.Count, Items = favs });
        if (recent.Count > 0) list.Add(new CatVM { Key = "recent", Name = "Vistos recentemente", Count = recent.Count, Items = recent });
        list.Add(new CatVM { Key = "all", Name = "Todos os canais", Count = _lib.AllChannels.Count, Items = _lib.AllChannels });
        foreach (var c in _lib.Channels.Where(c => c.Items.Count > 0))
            list.Add(new CatVM { Key = "c:" + c.Name, Name = c.Name, Count = c.Items.Count, Items = c.Items });
        Cats.ItemsSource = list;
        Cats.SelectedItem = list.FirstOrDefault(c => c.Key == selectKey) ?? list.FirstOrDefault(c => c.Key == "all");
    }

    // Os grupos não pegam foco (o teclado continua no app), e o ListBox do WPF só seleciona no clique quando
    // o item consegue pegar o foco — por isso clicar num grupo não fazia nada. A seleção é feita aqui.
    void Cat_Click(object sender, MouseButtonEventArgs e)
    {
        if (sender is not ListBoxItem { DataContext: CatVM c }) return;
        e.Handled = true;
        if (Cats.SelectedItem != c) Cats.SelectedItem = c;
        else if (_search != null) Cats_SelectionChanged(null, null);   // clicar no grupo atual depois de uma busca volta para ele
    }

    void Cats_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (Cats.SelectedItem is not CatVM c) return;
        _search = null;
        CatTitle.Text = c.Name.Replace("★  ", "");
        _current = c.Items.ToList();
        Show(keepScroll: false);
    }

    int Cols() => Math.Max(2, (int)((Math.Max(ChGrid.ActualWidth, 700) - 56 + 12) / (232 + 12)));

    void Show(bool keepScroll)
    {
        var sv = UI.Find<ScrollViewer>(ChGrid);
        double off = keepScroll && sv != null ? sv.VerticalOffset : 0;
        _cols = Cols();
        CatCount.Text = _current.Count == 1 ? "1 canal" : $"{_current.Count} canais";
        var rows = new List<object>();
        if (_current.Count == 0)
            rows.Add(_search != null
                ? new MessageVM { Title = $"Nenhum canal encontrado para \"{_search}\"", Text = "Tente outro nome." }
                : _lib.AllChannels.Count == 0
                    ? new MessageVM { Title = "Nenhum canal na sua lista ainda", Text = "Adicione canais no seu arquivo .m3u com group-title=\"CANAIS | Esportes\" (ou só o nome do grupo, ex.: \"Esportes\"). Salve e o StreamPLAY atualiza sozinho." }
                    : new MessageVM { Title = "Nada por aqui", Text = "" });
        _cards = _current.Select(i => new Card(i)).ToList();
        foreach (var r in UI.Rows(_cards, _cols)) rows.Add(new GridRowVM { Cards = r, Cols = _cols });
        ChGrid.ItemsSource = rows;
        if (sv != null)
        {
            Smooth.Reset(sv);
            if (off > 0) Dispatcher.BeginInvoke(DispatcherPriority.Loaded, () => sv.ScrollToVerticalOffset(off));
        }
    }

    public void Refresh()
    {
        var key = (Cats.SelectedItem as CatVM)?.Key;
        if (_search != null) return;
        var sv = UI.Find<ScrollViewer>(ChGrid);
        double off = sv?.VerticalOffset ?? 0;
        BuildCats(key);
        if (sv != null && off > 0) Dispatcher.BeginInvoke(DispatcherPriority.Loaded, () => sv.ScrollToVerticalOffset(off));
    }

    public void Search(string text)
    {
        if (string.IsNullOrEmpty(text))
        {
            if (_search == null) return;
            _search = null;
            Cats_SelectionChanged(null, null);
            return;
        }
        _search = text;
        var q = M3U.Norm(text);
        _current = _lib.AllChannels.Where(i => M3U.Norm(i.Name).Contains(q)).OrderBy(i => M3U.Norm(i.Name).StartsWith(q) ? 0 : 1).ToList();
        CatTitle.Text = $"Resultados para \"{text}\"";
        Show(keepScroll: false);
    }

    void OnClick(object sender, RoutedEventArgs e)
    {
        if (e.OriginalSource is not FrameworkElement fe || fe.DataContext is not Card c) return;
        switch (fe.Tag as string)
        {
            case "play":
                e.Handled = true;
                MainWindow.I.Play(PlayRequest.ForChannel(c.Item, _current));
                break;
            case "fav":
                e.Handled = true;
                c.IsFav = Store.ToggleFav(c.Item.Key);
                MainWindow.I.Toast(c.IsFav ? $"{c.Item.Name} adicionado aos favoritos" : $"{c.Item.Name} removido dos favoritos");
                var key = (Cats.SelectedItem as CatVM)?.Key;
                if (key == "fav" && !c.IsFav) BuildCats("fav");
                else if (_search == null)
                {
                    // só atualiza os contadores, mantendo a grade
                    Cats.SelectionChanged -= Cats_SelectionChanged;
                    BuildCats(key);
                    Cats.SelectionChanged += Cats_SelectionChanged;
                }
                break;
        }
    }
}
