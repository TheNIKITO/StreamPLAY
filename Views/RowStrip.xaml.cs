using System.Collections;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace StreamPLAY;

/// <summary>Fileira horizontal com setas de paginação (igual às fileiras da Netflix).</summary>
public partial class RowStrip : UserControl
{
    const double Gutter = 56;
    double _offset;

    public RowStrip()
    {
        InitializeComponent();
        Strip.SizeChanged += (_, _) => { Lane.Height = Strip.ActualHeight + 14 + 22; UpdateArrows(); };
        Viewport.SizeChanged += (_, _) => { Clamp(); UpdateArrows(); };
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
        var tpl = (DataTemplate)FindResource(vm.Kind == Kind.Channel ? "ChannelCard" : "PosterCard");
        double w = vm.Kind == Kind.Channel ? 230 : 168;
        Strip.ItemTemplate = new DataTemplate { VisualTree = Wrap(tpl, w) };
        Strip.ItemsSource = vm.Cards;
        _offset = 0;
        Shift.BeginAnimation(TranslateTransform.XProperty, null);
        Shift.X = 0;
        Margin = new Thickness(0, 0, 0, 6);
        UpdateArrows();
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
    double Max => Math.Max(0, Strip.ActualWidth - View);
    double Step
    {
        get
        {
            double card = (DataContext is RowVM { Kind: Kind.Channel } ? 230 : 168) + 8;
            return Math.Max(card, Math.Floor((View + 8) / card) * card);
        }
    }

    void Left_Click(object sender, RoutedEventArgs e) => Page(-1);
    void Right_Click(object sender, RoutedEventArgs e) => Page(1);

    void Page(int dir)
    {
        _offset = Math.Clamp(_offset + dir * Step, 0, Max);
        Shift.BeginAnimation(TranslateTransform.XProperty,
            new DoubleAnimation(-_offset, TimeSpan.FromMilliseconds(650)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseInOut } });
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
        UI.Fade(AllBtn, on ? 1 : 0, 160);
    }
}
