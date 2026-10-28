using System.Collections;
using System.Collections.ObjectModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace StreamPLAY;

/// <summary>
/// Fileira horizontal com setas de paginação (igual às fileiras da Netflix).
/// Só monta os cartazes que cabem na tela (mais uma folga) e vai montando o resto ao avançar: montar os 40 de
/// uma vez a cada fileira que entra na tela era o que travava a rolagem.
/// </summary>
public partial class RowStrip : UserControl
{
    const double Gutter = 56;
    double _offset;
    List<Card> _todos = new();
    readonly ObservableCollection<Card> _montados = new();
    static readonly Dictionary<string, DataTemplate> Modelos = new();

    public RowStrip()
    {
        InitializeComponent();
        Strip.ItemsSource = _montados;
        Strip.SizeChanged += (_, _) => { Lane.Height = Strip.ActualHeight + 14 + 22; UpdateArrows(); };
        Viewport.SizeChanged += (_, _) => { Montar(_offset); Clamp(); UpdateArrows(); };
        Root.MouseEnter += (_, _) => { Show(true); };
        Root.MouseLeave += (_, _) => { Show(false); };
        DataContextChanged += (_, _) => Bind();
    }

    public static readonly DependencyProperty CardWidthProperty =
        DependencyProperty.Register(nameof(CardWidth), typeof(double), typeof(RowStrip), new PropertyMetadata(168.0));
    public double CardWidth { get => (double)GetValue(CardWidthProperty); set => SetValue(CardWidthProperty, value); }

    void Bind()
    {
        if (DataContext is not RowVM vm) return;
        TitleText.Text = vm.Title;
        AllBtn.Visibility = vm.CanShowAll ? Visibility.Visible : Visibility.Collapsed;
        AllText.Text = vm.Total > vm.Cards.Count ? $"Ver tudo ({vm.Total})" : "Ver tudo";
        var nome = vm.Kind == Kind.Channel ? "ChannelCard" : vm.Continue ? "ContinueCard" : "PosterCard";
        if (!Modelos.TryGetValue(nome, out var modelo))
        {
            modelo = Modelos[nome] = new DataTemplate { VisualTree = Wrap((DataTemplate)FindResource(nome), vm.Kind == Kind.Channel ? 230 : 168) };
            modelo.Seal();
        }
        // setas no meio da imagem dos cartazes
        double meio = 14 + (vm.Kind == Kind.Channel ? 230 * 0.68 : 168 * 1.5) / 2 - 22;
        LeftBtn.Margin = new Thickness(12, meio, 0, 0);
        RightBtn.Margin = new Thickness(0, meio, 12, 0);
        if (Strip.ItemTemplate != modelo) Strip.ItemTemplate = modelo;
        _todos = vm.Cards ?? new List<Card>();
        _montados.Clear();
        _offset = 0;
        Shift.BeginAnimation(TranslateTransform.XProperty, null);
        Shift.X = 0;
        Montar(0);
        Margin = new Thickness(0, 0, 0, 6);
        UpdateArrows();
    }

    double Cartao => (DataContext is RowVM { Kind: Kind.Channel } ? 230 : 168) + 8;

    // Garante montados os cartazes que aparecem com a fileira deslocada em "ate" (mais um de folga).
    void Montar(double ate)
    {
        double largura = Viewport.ActualWidth > 0 ? Viewport.ActualWidth : (MainWindow.I?.ActualWidth ?? 1920);
        int precisa = Math.Min(_todos.Count, (int)Math.Ceiling((ate + largura) / Cartao) + 1);
        for (int i = _montados.Count; i < precisa; i++) _montados.Add(_todos[i]);
    }

    // Embrulha o template do card num ContentPresenter com largura fixa e espaçamento.
    static FrameworkElementFactory Wrap(DataTemplate tpl, double width)
    {
        var f = new FrameworkElementFactory(typeof(ContentPresenter));
        f.SetValue(ContentPresenter.ContentTemplateProperty, tpl);
        f.SetBinding(ContentPresenter.ContentProperty, new System.Windows.Data.Binding());
        f.SetValue(WidthProperty, width);
        f.SetValue(MarginProperty, new Thickness(0, 0, 8, 0));
        return f;
    }

    double View => Math.Max(0, Viewport.ActualWidth - 2 * Gutter);
    // largura da fileira inteira, contando os cartazes que ainda não foram montados
    double Max => Math.Max(0, _todos.Count * Cartao - 8 - View);
    double Step => Math.Max(Cartao, Math.Floor((View + 8) / Cartao) * Cartao);

    void Left_Click(object sender, RoutedEventArgs e) => Page(-1);
    void Right_Click(object sender, RoutedEventArgs e) => Page(1);

    void Page(int dir)
    {
        _offset = Math.Clamp(_offset + dir * Step, 0, Max);
        Montar(_offset);
        var anim = new DoubleAnimation(-_offset, TimeSpan.FromMilliseconds(650)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseInOut } };
        // terminada a animação, deixa a página seguinte montada (com o PC parado) para o próximo clique não travar
        anim.Completed += (_, _) => Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.Background, () => Montar(_offset + Step));
        Shift.BeginAnimation(TranslateTransform.XProperty, anim);
        UpdateArrows();
    }

    void Clamp()
    {
        if (_offset <= Max) return;
        _offset = Max;
        Shift.BeginAnimation(TranslateTransform.XProperty, null);
        Shift.X = -_offset;
    }

    void UpdateArrows()
    {
        LeftBtn.Visibility = _offset > 1 ? Visibility.Visible : Visibility.Collapsed;
        RightBtn.Visibility = _offset < Max - 1 ? Visibility.Visible : Visibility.Collapsed;
    }

    void Show(bool on)
    {
        UI.Fade(LeftBtn, on ? 1 : 0, 160);
        UI.Fade(RightBtn, on ? 1 : 0, 160);
    }
}
