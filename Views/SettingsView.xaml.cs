using System.Diagnostics;
using System.Reflection;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;

namespace StreamPLAY;

public partial class SettingsView : UserControl
{
    public SettingsView()
    {
        InitializeComponent();
        var src = Store.D.Source ?? "";
        bool file = !M3U.IsUrl(src);
        // o link da lista online não aparece (é do painel, não do cliente); arquivo do PC mostra o caminho
        SourceText.Text = src;
        SourceText.ToolTip = src;
        SourceText.Visibility = file ? Visibility.Visible : Visibility.Collapsed;
        CountText.Text = MainWindow.I.Lib?.Summary ?? "";
        WatchText.Text = file ? "● Atualização automática ligada: salve o arquivo e o app reorganiza sozinho." : "● Lista online: o app confere novidades sozinho a cada 10 minutos (e quando você volta para ele).";
        EditBtn.Visibility = FolderBtn.Visibility = file ? Visibility.Visible : Visibility.Collapsed;
        if (MainWindow.I.ListaDoPainel) ChangeBtn.Visibility = Visibility.Collapsed;
        var conta = MainWindow.I.ContaAtual?.Acesso;
        ContaText.Text = $"Conectado como {Store.D.Login}";
        var sub = new List<string>();
        if (!string.IsNullOrWhiteSpace(conta?.Plano)) sub.Add(conta.Plano);
        if (conta?.FaturaVenc is { } venc) sub.Add($"próximo vencimento {venc:dd/MM/yyyy} ({Conta.Dinheiro(conta.FaturaValor)})");
        var linha = string.Join("  •  ", sub);
        ContaSub.Text = linha.Length > 0 ? char.ToUpper(linha[0]) + linha[1..] : "Acesso liberado.";
        FullBox.IsChecked = Store.D.TelaCheiaAoAbrir;
        NextBox.IsChecked = Store.D.AutoNext;
        DiscordBox.IsChecked = Store.D.DiscordStatus;

        foreach (var ms in new[] { 1000, 1500, 3000, 5000, 8000 })
        {
            var rb = new RadioButton
            {
                Style = (Style)FindResource("Chip"), Content = $"{ms / 1000.0:0.#} s".Replace('.', ','), GroupName = "cache", IsChecked = Store.D.Caching == ms,
            };
            int v = ms;
            rb.Checked += (_, _) => { Store.D.Caching = v; Store.Save(); };
            CachePanel.Children.Add(rb);
        }

        var ver = Assembly.GetExecutingAssembly().GetName().Version;
        string vlc = "";
        try { vlc = App.VLC?.Version?.Split(' ')[0]; } catch { }
        VersionText.Text = $"StreamPLAY {Updater.Rotulo(ver?.ToString(3))}" + (string.IsNullOrEmpty(vlc) ? "" : $"  •  motor VLC {vlc}{(App.VlcEhEmbutido ? " (embutido)" : "")}");

        ShowUpdateStatus();
        MainWindow.I.UpdateStatusChanged += ShowUpdateStatus;
        Unloaded += (_, _) => MainWindow.I.UpdateStatusChanged -= ShowUpdateStatus;
    }

    void ShowUpdateStatus()
    {
        var s = MainWindow.I.UpdateStatus;
        UpdateStatusText.Text = string.IsNullOrEmpty(s)
            ? $"Versão {Updater.Rotulo(Updater.CurrentText)}. O app procura atualizações sozinho ao abrir e a cada 30 minutos."
            : s;
        UpdateBtn.Content = Updater.Ready != null ? "Reiniciar e atualizar" : "Procurar atualizações";
    }

    async void Update_Click(object sender, RoutedEventArgs e)
    {
        if (Updater.Ready != null) { MainWindow.I.InstallUpdateNow(); return; }
        UpdateBtn.IsEnabled = false;
        await MainWindow.I.CheckUpdates(manual: true);
        UpdateBtn.IsEnabled = true;
        ShowUpdateStatus();
    }

    void Opt_Click(object sender, RoutedEventArgs e)
    {
        Store.D.TelaCheiaAoAbrir = FullBox.IsChecked == true;
        Store.D.AutoNext = NextBox.IsChecked == true;
        Store.D.DiscordStatus = DiscordBox.IsChecked == true;
        if (!Store.D.DiscordStatus) Discord.Mostrar(null);   // desligou: some na hora (ligando, o player mostra no próximo instante)
        Store.Save();
    }

    void Edit_Click(object sender, RoutedEventArgs e)
    {
        try { Process.Start(new ProcessStartInfo("notepad.exe", $"\"{Store.D.Source}\"") { UseShellExecute = true }); } catch { }
    }

    void Folder_Click(object sender, RoutedEventArgs e)
    {
        try { Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{Store.D.Source}\"") { UseShellExecute = true }); } catch { }
    }

    async void Reload_Click(object sender, RoutedEventArgs e)
    {
        MainWindow.I.CloseOverlay();
        await MainWindow.I.LoadLibrary(quiet: true);
    }

    void Change_Click(object sender, RoutedEventArgs e) => MainWindow.I.ShowSetup(null);

    void ClearProgress_Click(object sender, RoutedEventArgs e)
    {
        if (MessageBox.Show("Apagar o progresso de tudo que você estava assistindo?", "StreamPLAY", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
        Store.D.Progress.Clear();
        // os outros aparelhos da conta também limpam (a marca vai junto na sincronização)
        Store.D.ProgressoLimpo = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        Sincronia.Sujo = true;
        Store.Save();
        MainWindow.I.EnviarSincronia();
        (MainWindow.I.PageHost.Content as IPage)?.Refresh();
        MainWindow.I.Toast("\"Continuar assistindo\" foi limpo");
    }

    void Sair_Click(object sender, RoutedEventArgs e)
    {
        if (MessageBox.Show("Sair da sua conta? Para usar o StreamPLAY de novo, será preciso digitar o login e a senha.", "StreamPLAY",
                MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
        MainWindow.I.SairDaConta(null);
    }

    void Close_Click(object sender, RoutedEventArgs e) => MainWindow.I.CloseOverlay();

    void Dim_MouseDown(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource == sender) MainWindow.I.CloseOverlay();
    }
}
