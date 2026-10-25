using System.Windows;
using System.Windows.Controls;
using System.Windows.Automation;
using System.Windows.Input;
using System.Windows.Media;

namespace StreamPLAY;

/// <summary>Janela "Mais informações" (filme ou série com temporadas/episódios).</summary>
public partial class DetailsView : UserControl
{
    readonly Item _item;
    int _season;

    public DetailsView(Item item)
    {
        InitializeComponent();
        _item = item;
        SizeChanged += (_, _) => CardBox.Width = Math.Min(900, Math.Max(600, ActualWidth - 80));
        Fill();
        Refresh();
    }

    void Fill()
    {
        KindText.Text = _item.KindLabel;
        TitleText.Text = _item.Name;
        if (_item.HasBackdrop) Img.SetSrc(Backdrop, _item.Backdrop);
        else
        {
            PosterArt.Visibility = Visibility.Visible;
            Img.SetSrc(Blur, _item.Logo);
            Img.SetSrc(Poster, _item.Logo);
        }

        MetaPanel.Children.Clear();
        if (_item.IsRelease) MetaPanel.Children.Add(Badge("LANÇAMENTO"));
        if (!string.IsNullOrEmpty(_item.Year)) MetaPanel.Children.Add(Meta(_item.Year, "#BCBCBC"));
        if (!string.IsNullOrEmpty(_item.Rating)) MetaPanel.Children.Add(Meta("★ " + _item.Rating, "#46D369"));
        if (_item.Kind == Kind.Series)
        {
            int s = _item.Seasons.Count;
            MetaPanel.Children.Add(Meta(s > 1 ? $"{s} temporadas" : _item.Episodes.Count == 1 ? "1 episódio" : $"{_item.Episodes.Count} episódios", "#BCBCBC"));
        }
        MetaPanel.Children.Add(new Border
        {
            BorderBrush = new SolidColorBrush(Color.FromRgb(0x80, 0x80, 0x80)), BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(3),
            Padding = new Thickness(6, 0, 6, 1), Margin = new Thickness(0, 0, 10, 0), VerticalAlignment = VerticalAlignment.Center,
            Child = new TextBlock { Text = "HD", FontSize = 11, FontWeight = FontWeights.SemiBold, Foreground = Brushes.White },
        });

        PlotText.Text = _item.Plot ?? (_item.Kind == Kind.Series ? "Escolha a temporada e o episódio abaixo." : "");
        PlotText.Visibility = string.IsNullOrEmpty(PlotText.Text) ? Visibility.Collapsed : Visibility.Visible;
        GenreText.Inlines.Clear();
        if (!string.IsNullOrEmpty(_item.Genre)) { GenreText.Inlines.Add("Gênero: "); GenreText.Inlines.Add(new System.Windows.Documents.Run(_item.Genre) { Foreground = Brushes.White }); }
        else GenreText.Visibility = Visibility.Collapsed;
        CatText.Inlines.Clear();
        CatText.Inlines.Add(_item.Categories.Count > 1 ? "Categorias: " : "Categoria: ");
        CatText.Inlines.Add(new System.Windows.Documents.Run(string.Join(", ", _item.Categories)) { Foreground = Brushes.White });
    }

    static Border Badge(string text) => new()
    {
        Background = (Brush)Application.Current.FindResource("Red"), CornerRadius = new CornerRadius(3), Padding = new Thickness(7, 2, 7, 2),
        Margin = new Thickness(0, 0, 12, 0), VerticalAlignment = VerticalAlignment.Center,
        Child = new TextBlock { Text = text, FontSize = 11, FontWeight = FontWeights.Bold, Foreground = Brushes.White },
    };

    static TextBlock Meta(string text, string color) => new()
    {
        Text = text, FontSize = 15, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 0, 12, 0), VerticalAlignment = VerticalAlignment.Center,
        Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString(color)),
    };

    public void Refresh()
    {
        bool fav = Store.IsFav(_item.Key);
        FavIcon.Data = (Geometry)FindResource(fav ? "G.Check" : "G.Plus");
        FavBtn.ToolTip = fav ? "Remover da Minha lista" : "Adicionar à Minha lista";

        Progress p = null;
        if (_item.Kind == Kind.Movie)
        {
            p = Store.GetProgress(_item.Key);
            if (p != null && (p.Finished || p.Fraction < 0.01)) p = null;
            PlayText.Text = p != null ? "Continuar" : "Assistir";
        }
        else
        {
            var ep = Resume.EpisodeFor(_item);
            if (ep != null)
            {
                p = Store.GetProgress(ep.Key);
                if (p != null && (p.Finished || p.Fraction < 0.01)) p = null;
                bool started = p != null || ep != _item.Episodes[0];
                PlayText.Text = started ? $"Continuar {ep.Label}" : $"Assistir {ep.Label}";
                if (_season == 0) _season = ep.Season;
            }
            EpisodesBox.Visibility = Visibility.Visible;
            BuildSeasons();
            BuildEpisodes();
        }
        RestartBtn.Visibility = p != null ? Visibility.Visible : Visibility.Collapsed;
        ProgressRow.Visibility = p != null ? Visibility.Visible : Visibility.Collapsed;
        if (p != null)
        {
            ProgressFill.Width = 220 * p.Fraction;
            ProgressText.Text = UI.Left(p.Dur - p.Pos);
        }
    }

    void BuildSeasons()
    {
        var seasons = _item.Seasons;
        Seasons.Children.Clear();
        SeasonLabel.Text = seasons.Count > 1 ? "" : $"Temporada {seasons.FirstOrDefault()}";
        if (seasons.Count <= 1) return;
        foreach (var s in seasons)
        {
            var rb = new RadioButton { Style = (Style)FindResource("Chip"), Content = $"Temporada {s}", GroupName = "season" + GetHashCode(), IsChecked = s == _season };
            int season = s;
            rb.Checked += (_, _) => { _season = season; BuildEpisodes(); };
            Seasons.Children.Add(rb);
        }
    }

    void BuildEpisodes()
    {
        EpisodeList.Children.Clear();
        var current = Resume.EpisodeFor(_item);
        foreach (var ep in _item.Episodes.Where(e => e.Season == _season))
        {
            var p = Store.GetProgress(ep.Key);
            var grid = new Grid { Margin = new Thickness(0) };
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(56) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(160) });
            grid.ColumnDefinitions.Add(new ColumnDefinition());

            grid.Children.Add(new TextBlock
            {
                Text = ep.Number.ToString(), FontSize = 24, Foreground = new SolidColorBrush(Color.FromRgb(0xD2, 0xD2, 0xD2)),
                HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
            });

            var thumb = new Grid { Height = 90, Margin = new Thickness(0, 0, 18, 0) };
            thumb.Children.Add(new Border { Background = new SolidColorBrush(Color.FromRgb(0x2A, 0x2A, 0x2A)), CornerRadius = new CornerRadius(4) });
            var art = new Border { CornerRadius = new CornerRadius(4) };
            Img.SetDecode(art, 280);
            Img.SetSrc(art, ep.Image);
            thumb.Children.Add(art);
            var playIco = new Border
            {
                Width = 40, Height = 40, CornerRadius = new CornerRadius(20), Background = new SolidColorBrush(Color.FromArgb(0x99, 0, 0, 0)),
                BorderBrush = Brushes.White, BorderThickness = new Thickness(1.5), Opacity = 0,
                Child = new Viewbox { Margin = new Thickness(11, 10, 9, 10), Child = UI.Icon("G.Play", false) },
            };
            thumb.Children.Add(playIco);
            if (p != null && !p.Finished && p.Fraction > 0.01)
            {
                var bar = new Grid { Height = 3, VerticalAlignment = VerticalAlignment.Bottom };
                bar.Children.Add(new Border { Background = new SolidColorBrush(Color.FromRgb(0x5A, 0x5A, 0x5A)) });
                bar.Children.Add(new Border
                {
                    Background = (Brush)FindResource("Red"), RenderTransformOrigin = new Point(0, 0.5),
                    RenderTransform = new ScaleTransform(p.Fraction, 1),
                });
                thumb.Children.Add(bar);
            }
            Grid.SetColumn(thumb, 1);
            grid.Children.Add(thumb);

            var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            var title = new DockPanel();
            if (p != null && p.Dur > 0)
                title.Children.Add(DockRight(new TextBlock { Text = p.Finished ? "Assistido" : UI.Time(p.Dur), Foreground = new SolidColorBrush(Color.FromRgb(0xB3, 0xB3, 0xB3)), FontSize = 14, Margin = new Thickness(12, 0, 0, 0) }));
            title.Children.Add(new TextBlock { Text = ep.Title, FontSize = 16, FontWeight = FontWeights.SemiBold, Foreground = Brushes.White, TextTrimming = TextTrimming.CharacterEllipsis });
            text.Children.Add(title);
            if (!string.IsNullOrEmpty(ep.Plot))
                text.Children.Add(new TextBlock { Text = ep.Plot, FontSize = 14, Foreground = new SolidColorBrush(Color.FromRgb(0xB3, 0xB3, 0xB3)), TextWrapping = TextWrapping.Wrap, MaxHeight = 60, TextTrimming = TextTrimming.WordEllipsis, Margin = new Thickness(0, 6, 0, 0), LineHeight = 20 });
            Grid.SetColumn(text, 2);
            grid.Children.Add(text);

            var row = new Button
            {
                Style = (Style)FindResource("RowBtn"), Content = grid, Padding = new Thickness(0, 16, 16, 16),
                Background = ep == current ? new SolidColorBrush(Color.FromRgb(0x33, 0x33, 0x33)) : Brushes.Transparent,
                BorderBrush = new SolidColorBrush(Color.FromRgb(0x40, 0x40, 0x40)), BorderThickness = new Thickness(0, 0, 0, 1),
            };
            AutomationProperties.SetName(row, $"{ep.Label} {ep.Title}");
            var target = ep;
            row.Click += (_, _) => MainWindow.I.Play(PlayRequest.ForEpisode(target));
            row.MouseEnter += (_, _) => playIco.Opacity = 1;
            row.MouseLeave += (_, _) => playIco.Opacity = 0;
            EpisodeList.Children.Add(row);
        }
    }

    static UIElement DockRight(UIElement e) { DockPanel.SetDock(e, Dock.Right); return e; }

    void Play_Click(object sender, RoutedEventArgs e)
    {
        if (_item.Kind == Kind.Movie) MainWindow.I.Play(PlayRequest.ForItem(_item));
        else
        {
            var ep = Resume.EpisodeFor(_item);
            if (ep != null) MainWindow.I.Play(PlayRequest.ForEpisode(ep));
        }
    }

    void Restart_Click(object sender, RoutedEventArgs e)
    {
        var req = _item.Kind == Kind.Movie ? PlayRequest.ForItem(_item) : PlayRequest.ForEpisode(Resume.EpisodeFor(_item));
        if (req == null) return;
        req.FromStart = true;
        MainWindow.I.Play(req);
    }

    void Fav_Click(object sender, RoutedEventArgs e)
    {
        bool on = Store.ToggleFav(_item.Key);
        MainWindow.I.Toast(on ? "Adicionado à Minha lista" : "Removido da Minha lista");
        Refresh();
        (MainWindow.I.PageHost.Content as IPage)?.Refresh();
    }

    void Close_Click(object sender, RoutedEventArgs e) => MainWindow.I.CloseOverlay();

    void Dim_MouseDown(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource == sender) MainWindow.I.CloseOverlay();
    }
}
