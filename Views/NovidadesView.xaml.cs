using System.Diagnostics;
using System.Globalization;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;

namespace StreamPLAY;

/// <summary>Popup das novidades do PLAYGER (aparece uma vez ao abrir o app; veja Novidades).</summary>
public partial class NovidadesView : UserControl
{
    static readonly CultureInfo Br = new("pt-BR");
    static readonly Brush Sub = new SolidColorBrush(Color.FromRgb(0xC4, 0xC4, 0xCC));

    public NovidadesView(IReadOnlyList<Novidades.Novidade> lista)
    {
        InitializeComponent();
        bool primeira = true;
        foreach (var n in lista)
        {
            var bloco = new StackPanel { Margin = new Thickness(0, primeira ? 0 : 22, 0, 0) };
            primeira = false;
            bloco.Children.Add(new TextBlock { Text = n.Data.ToString("d 'de' MMMM 'de' yyyy", Br), Foreground = new SolidColorBrush(Color.FromRgb(0xFF, 0x2A, 0x35)), FontSize = 13, FontWeight = FontWeights.SemiBold });
            bloco.Children.Add(new TextBlock { Text = n.Titulo, FontSize = 19, FontWeight = FontWeights.Bold, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 4, 0, 6) });
            // como no site: linha com "-" vira item de lista; linha em branco separa parágrafos
            foreach (var linha in n.Texto.Replace("\r", "").Split('\n'))
            {
                if (string.IsNullOrWhiteSpace(linha)) { bloco.Children.Add(new Border { Height = 6 }); continue; }
                var item = Regex.Match(linha, @"^\s*[-•*]\s+(.*)$");
                bloco.Children.Add(new TextBlock
                {
                    Text = item.Success ? "•  " + item.Groups[1].Value.Trim() : linha.Trim(), Foreground = Sub, FontSize = 15, TextWrapping = TextWrapping.Wrap,
                    Margin = new Thickness(item.Success ? 8 : 0, 2, 0, 2), LineHeight = 22,
                });
            }
            Lista.Children.Add(bloco);
        }
        Loaded += (_, _) => OkBtn.Focus();
        PreviewKeyDown += (_, e) => { if (e.Key == Key.Escape) { MainWindow.I.CloseOverlay(); e.Handled = true; } };
    }

    void Close_Click(object sender, RoutedEventArgs e) => MainWindow.I.CloseOverlay();

    void Site_Click(object sender, RoutedEventArgs e)
    {
        try { Process.Start(new ProcessStartInfo(Novidades.Site) { UseShellExecute = true }); } catch { }
    }

    void Dim_MouseDown(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource == sender) MainWindow.I.CloseOverlay();
    }
}
