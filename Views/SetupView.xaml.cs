using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using Microsoft.Win32;

namespace StreamPLAY;

public partial class SetupView : UserControl
{
    public const string GuideText =
        "FILMES  →  group-title=\"FILMES\"\n" +
        "LANÇAMENTOS  →  group-title=\"FILMES | Lançamentos\"\n" +
        "Outras fileiras  →  group-title=\"FILMES | Ação\"\n\n" +
        "SÉRIES  →  group-title=\"SERIES | Drama\"\n" +
        "   e o nome do episódio assim: \"Nome da Série S01E01\"\n\n" +
        "CANAIS  →  group-title=\"CANAIS = Esportes\"  (ou \"CANAIS | Esportes\")\n" +
        "   os canais também podem ficar num canais.m3u ao lado da lista (mesmo link/pasta)\n" +
        "   (qualquer outro grupo também vira canal)\n\n" +
        "Exemplo:\n" +
        "#EXTINF:-1 tvg-name=\"Nome do filme\" tvg-logo=\"http://capa.jpg\" group-title=\"FILMES | Lançamentos\"\n" +
        "http://link-do-video.mp4\n\n" +
        "Opcional na linha #EXTINF: descricao=\"...\" ano=\"2024\" genero=\"Ação\" nota=\"8.5\" fundo=\"http://imagem-larga.jpg\"";

    public SetupView(string error)
    {
        InitializeComponent();
        Guide.Text = GuideText;
        if (!string.IsNullOrEmpty(error)) { ErrorText.Text = error; ErrorText.Visibility = Visibility.Visible; }
        if (MainWindow.I?.Lib != null && string.IsNullOrEmpty(error)) BackBtn.Visibility = Visibility.Visible;
    }

    void Back_Click(object sender, RoutedEventArgs e) => MainWindow.I.LeaveSetup();

    void Pick_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFileDialog
        {
            Title = "Escolha sua lista",
            Filter = "Listas M3U (*.m3u;*.m3u8;*.txt)|*.m3u;*.m3u8;*.txt|Todos os arquivos|*.*",
        };
        if (dlg.ShowDialog(Window.GetWindow(this)) == true) Use(dlg.FileName);
    }

    void New_Click(object sender, RoutedEventArgs e)
    {
        var path = CreateStarter();
        if (path == null) return;
        Use(path);
        try { Process.Start(new ProcessStartInfo("notepad.exe", $"\"{path}\"") { UseShellExecute = true }); } catch { }
    }

    public static string CreateStarter()
    {
        try
        {
            var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "StreamPLAY");
            Directory.CreateDirectory(dir);
            var path = Path.Combine(dir, "minha-lista.m3u");
            if (!File.Exists(path))
                File.WriteAllText(path,
                    "#EXTM3U\n" +
                    "# ===== Lista do StreamPLAY =====\n" +
                    "# FILMES:       group-title=\"FILMES\"   ou   group-title=\"FILMES | Lançamentos\"\n" +
                    "# SERIES:       group-title=\"SERIES | Drama\"  e o nome no formato \"Nome da Série S01E01\"\n" +
                    "# CANAIS:       group-title=\"CANAIS | Esportes\"\n" +
                    "# Salve este arquivo (Ctrl+S) e o StreamPLAY atualiza sozinho.\n" +
                    "\n" +
                    "#EXTINF:-1 tvg-name=\"Big Buck Bunny\" tvg-logo=\"\" group-title=\"FILMES | Lançamentos\" ano=\"2008\" descricao=\"Exemplo (filme aberto da Blender Foundation). Pode apagar.\",Big Buck Bunny\n" +
                    "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8\n",
                    new System.Text.UTF8Encoding(false));
            return path;
        }
        catch (Exception ex) { MessageBox.Show("Não consegui criar a lista: " + ex.Message, "StreamPLAY"); return null; }
    }

    void Url_Click(object sender, RoutedEventArgs e) => UseUrl();

    void UrlBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter) { UseUrl(); e.Handled = true; }
    }

    void UseUrl()
    {
        var url = UrlBox.Text.Trim();
        if (!M3U.IsUrl(url))
        {
            ErrorText.Text = "Cole um link que comece com http:// ou https://";
            ErrorText.Visibility = Visibility.Visible;
            return;
        }
        Use(url);
    }

    void Use(string source)
    {
        ErrorText.Visibility = Visibility.Collapsed;
        Loading.Visibility = Visibility.Visible;
        MainWindow.I.UseSource(source, new Progress<double>(p =>
        {
            LoadingFill.Width = p * LoadingBar.Width;
            LoadingText.Text = $"{(int)Math.Round(p * 100)}%";
        }));
    }
}
