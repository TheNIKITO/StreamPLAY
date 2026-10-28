using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;

namespace StreamPLAY;

public class CatVM
{
    public string Key { get; init; }
    public string Name { get; init; }
    public int Count { get; init; }
    public IList<Item> Items { get; init; }
    public bool Selected { get; init; }
}

/// <summary>Linha da lista de canais: o número do canal (fixo, pela ordem de "Todos") e o cartão com o guia.</summary>
public class ChRow
{
    public int Numero { get; init; }
    public Card Card { get; init; }
    public string Name => Card.Name;
}

/// <summary>
/// Aba Canais: os grupos (Todos, Favoritos, Esportes...) ficam em cima; os canais do grupo numa lista com rolagem à
/// esquerda; à direita, o canal escolhido com o que está passando, os próximos programas, Assistir e Favoritar.
/// Clique escolhe, duplo clique (ou Assistir) toca.
/// </summary>
public partial class ChannelsView : UserControl, IPage
{
    readonly Library _lib;
    readonly Dictionary<string, int> _numeros = new();
    List<Item> _current = new();
    List<ChRow> _rows = new();
    string _catKey = "all";
    string _search;
    bool _montando;
    readonly DispatcherTimer _guiaTimer = new() { Interval = TimeSpan.FromSeconds(30) };

    public ChannelsView(Library lib)
    {
        InitializeComponent();
        _lib = lib;
        for (int i = 0; i < lib.AllChannels.Count; i++) _numeros[lib.AllChannels[i].Key] = i + 1;
        _guiaTimer.Tick += (_, _) => AtualizarGuia();
        Loaded += (_, _) => { Guia.Mudou += AtualizarGuia; _guiaTimer.Start(); AtualizarGuia(); };
        Unloaded += (_, _) => { Guia.Mudou -= AtualizarGuia; _guiaTimer.Stop(); };
        BuildCats(null);
    }

    Item Selecionado => (ChList.SelectedItem as ChRow)?.Card.Item;

    void AtualizarGuia()
    {
        var agora = DateTime.Now;
        foreach (var r in _rows) r.Card.AtualizarGuia(agora);
        MostrarPainel();
    }

    // ---------- grupos ----------

    void BuildCats(string selectKey)
    {
        selectKey ??= _catKey;
        var favs = Store.D.Favorites.Select(_lib.Get).Where(i => i?.Kind == Kind.Channel).ToList();
        var recent = Store.D.Recent.Select(_lib.Get).Where(i => i?.Kind == Kind.Channel).ToList();
        var lista = new List<(string key, string name, IList<Item> items)> { ("all", "Todos", _lib.AllChannels) };
        if (favs.Count > 0) lista.Add(("fav", "★ Favoritos", favs));
        if (recent.Count > 0) lista.Add(("recent", "Recentes", recent));
        foreach (var c in _lib.Channels.Where(c => c.Items.Count > 0)) lista.Add(("c:" + c.Name, c.Name, c.Items));
        if (!lista.Any(c => c.key == selectKey)) selectKey = "all";
        _montando = true;
        Cats.ItemsSource = lista.Select(c => new CatVM { Key = c.key, Name = c.name, Count = c.items.Count, Items = c.items, Selected = c.key == selectKey }).ToList();
        _montando = false;
        Escolher(selectKey);
    }

    void Cat_Checked(object sender, RoutedEventArgs e)
    {
        if (_montando || sender is not FrameworkElement { DataContext: CatVM c }) return;
        if (c.Key != _catKey || _search != null) Escolher(c.Key);
    }

    void Escolher(string key)
    {
        if (Cats.ItemsSource is not List<CatVM> cats) return;
        var c = cats.FirstOrDefault(x => x.Key == key) ?? cats[0];
        _catKey = c.Key;
        _search = null;
        CatTitle.Text = c.Key == "all" ? "Canais" : c.Name.Replace("★ ", "");
        _current = c.Items.ToList();
        Show(manterCanal: true);
    }

    // ---------- lista ----------

    void Show(bool manterCanal)
    {
        var antes = manterCanal ? Selecionado?.Key : null;
        CatCount.Text = _current.Count == 1 ? "1 canal" : $"{_current.Count} canais";
        _rows = _current.Select(i => new ChRow { Numero = _numeros.GetValueOrDefault(i.Key), Card = new Card(i) }).ToList();
        ChList.ItemsSource = _rows;
        Vazio.Visibility = _rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        Painel.Visibility = _rows.Count == 0 ? Visibility.Hidden : Visibility.Visible;
        if (_rows.Count == 0)
        {
            (VazioTitulo.Text, VazioTexto.Text) = _search != null
                ? ($"Nenhum canal encontrado para \"{_search}\"", "Tente outro nome.")
                : _lib.AllChannels.Count == 0
                    ? ("Nenhum canal na sua lista ainda", "Adicione canais no seu arquivo .m3u com group-title=\"CANAIS | Esportes\" (ou só o nome do grupo, ex.: \"Esportes\"). Salve e o StreamPLAY atualiza sozinho.")
                    : ("Nada por aqui", "");
            return;
        }
        // continua no mesmo canal se ele estiver no grupo; senão, o último assistido do grupo; senão, o primeiro
        var alvo = _rows.FirstOrDefault(r => r.Card.Item.Key == antes)
                   ?? Store.D.Recent.Select(k => _rows.FirstOrDefault(r => r.Card.Item.Key == k)).FirstOrDefault(r => r != null)
                   ?? _rows[0];
        ChList.SelectedItem = alvo;
        ChList.ScrollIntoView(alvo);
        if (UI.Find<ScrollViewer>(ChList) is { } sv && alvo == _rows[0]) Smooth.Reset(sv);
    }

    // Os itens não pegam foco (o teclado continua no app), e o ListBox do WPF só seleciona no clique quando o item
    // consegue pegar o foco: a seleção é feita aqui. Duplo clique toca.
    void Ch_Click(object sender, MouseButtonEventArgs e)
    {
        if (sender is not ListBoxItem { DataContext: ChRow r }) return;
        e.Handled = true;
        ChList.SelectedItem = r;
        if (e.ClickCount >= 2) Tocar(r.Card.Item);
    }

    void ChList_SelectionChanged(object sender, SelectionChangedEventArgs e) => MostrarPainel();

    // ---------- painel do canal ----------

    void MostrarPainel()
    {
        var item = Selecionado;
        if (item == null) return;
        var card = (ChList.SelectedItem as ChRow).Card;
        PNome.Text = item.Name;
        PIniciais.Text = card.Initials;
        if (Img.GetSrc(PLogo) != item.Logo) Img.SetSrc(PLogo, item.Logo);
        PCat.Text = item.Category;
        var agora = DateTime.Now;
        var (a, s) = Guia.AgoraESeguir(item, agora);
        bool tem = a != null || s != null;
        PGuia.Visibility = tem ? Visibility.Visible : Visibility.Collapsed;
        PSemGuia.Visibility = tem ? Visibility.Collapsed : Visibility.Visible;
        if (tem)
        {
            PAgora.Text = a?.Titulo ?? "Sem programa agora";
            PHorario.Text = a?.Horario ?? "";
            PProg.ScaleX = a?.Progresso(agora) ?? 0;
            PDesc.Text = a?.Descricao ?? "";
            PDesc.Visibility = string.IsNullOrEmpty(a?.Descricao) ? Visibility.Collapsed : Visibility.Visible;
            PProximos.ItemsSource = Guia.Proximos(item, agora, 5);
        }
        bool fav = Store.IsFav(item.Key);
        FavText.Text = fav ? "Favorito" : "Favoritar";
        FavIcon.Fill = fav ? new SolidColorBrush(Color.FromRgb(0xFF, 0xC1, 0x07)) : Brushes.Transparent;
        FavIcon.Stroke = fav ? FavIcon.Fill : Brushes.White;
    }

    void Assistir_Click(object sender, RoutedEventArgs e) { if (Selecionado is { } i) Tocar(i); }

    void Tocar(Item canal) => MainWindow.I.Play(PlayRequest.ForChannel(canal, _current));

    void Fav_Click(object sender, RoutedEventArgs e)
    {
        var row = ChList.SelectedItem as ChRow;
        if (row == null) return;
        row.Card.IsFav = Store.ToggleFav(row.Card.Item.Key);
        MainWindow.I.EnviarSincronia();
        MainWindow.I.Toast(row.Card.IsFav ? $"{row.Card.Name} adicionado aos favoritos" : $"{row.Card.Name} removido dos favoritos");
        if (_search == null) BuildCats(_catKey);   // atualiza os números dos grupos (e a lista, se for a de Favoritos)
        else MostrarPainel();
    }

    // ---------- página ----------

    public void Refresh()
    {
        if (_search != null) return;
        var sv = UI.Find<ScrollViewer>(ChList);
        double off = sv?.VerticalOffset ?? 0;
        BuildCats(_catKey);
        if (sv != null && off > 0) Dispatcher.BeginInvoke(DispatcherPriority.Loaded, () => sv.ScrollToVerticalOffset(off));
    }

    // a categoria escolhida fica; só a busca é desfeita
    public void Inicio() { if (_search != null) Escolher(_catKey); }

    public void Search(string text)
    {
        if (string.IsNullOrEmpty(text))
        {
            if (_search == null) return;
            Escolher(_catKey);
            return;
        }
        _search = text;
        var q = M3U.Norm(text);
        _current = _lib.AllChannels.Where(i => M3U.Norm(i.Name).Contains(q)).OrderBy(i => M3U.Norm(i.Name).StartsWith(q) ? 0 : 1).ToList();
        CatTitle.Text = $"Resultados para \"{text}\"";
        Show(manterCanal: false);
    }
}
