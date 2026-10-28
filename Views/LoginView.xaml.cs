using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace StreamPLAY;

/// <summary>Tela de entrada com a conta do PLAYGER (e o aviso de acesso bloqueado).</summary>
public partial class LoginView : UserControl
{
    bool _ocupado, _pixPedido;
    DispatcherTimer _espera;
    DateTime _esperaDesde;

    /// <summary>Mostra o formulário de entrar (com um aviso opcional).</summary>
    public LoginView(string aviso)
    {
        InitializeComponent();
        LoginBox.Text = Store.D.Login ?? "";
        if (!string.IsNullOrEmpty(aviso)) Erro(aviso);
        Loaded += (_, _) => (string.IsNullOrEmpty(LoginBox.Text) ? (Control)LoginBox : SenhaBox).Focus();
    }

    /// <summary>Mostra que a conta está bloqueada, com o motivo.</summary>
    public LoginView(Conta.Resultado bloqueio) : this((string)null) => MostrarBloqueio(bloqueio);

    void MostrarBloqueio(Conta.Resultado r)
    {
        LoginPanel.Visibility = Visibility.Collapsed;
        BloqPanel.Visibility = Visibility.Visible;
        BloqTitulo.Text = r.Titulo;
        BloqMsg.Text = r.Mensagem;
        var pix = r.Acesso?.Pix;
        bool porPagamento = r.Acesso is { Ativo: true, Manual: false } && !r.Aparelhos;
        PixBox.Visibility = porPagamento && !string.IsNullOrWhiteSpace(pix) ? Visibility.Visible : Visibility.Collapsed;
        PixText.Text = pix ?? "";
        DeNovoText.Text = porPagamento ? "Já paguei, verificar de novo" : "Verificar de novo";
        AutomationProperties.SetName(DeNovoBtn, DeNovoText.Text);
        BloqStatus.Visibility = Visibility.Collapsed;
        // Pix automático ligado no painel: QR Code com o valor certo no lugar da chave
        var url = r.Acesso?.PixUrl;
        if (porPagamento && !string.IsNullOrWhiteSpace(url) && !string.IsNullOrEmpty(Store.D.AcessoId))
        {
            PixBox.Visibility = Visibility.Collapsed;
            PixAuto.Visibility = Visibility.Visible;
            if (!_pixPedido) _ = MostrarPix(url, pix);
        }
        else PixAuto.Visibility = Visibility.Collapsed;
    }

    async Task MostrarPix(string url, string chave)
    {
        _pixPedido = true;
        PixAutoTitulo.Text = "Gerando o Pix...";
        try
        {
            var p = await Conta.PixAsync(url, Store.D.AcessoId);
            if (!p.Pendente) { PixAuto.Visibility = Visibility.Collapsed; Esperar(); return; }   // já pago: só falta o acesso atualizar
            PixAutoTitulo.Text = $"Pague com Pix: {Conta.Dinheiro(p.Valor)}" + (p.Faturas > 1 ? $"  ({p.Faturas} faturas)" : "");
            if (!string.IsNullOrEmpty(p.Qr))
            {
                var img = new BitmapImage();
                img.BeginInit();
                img.CacheOption = BitmapCacheOption.OnLoad;
                img.StreamSource = new MemoryStream(Convert.FromBase64String(p.Qr));
                img.EndInit();
                img.Freeze();
                PixQr.Source = img;
                PixQrBox.Visibility = Visibility.Visible;
            }
            PixCopia.Text = p.Copia ?? "";
            PixCopiaLinha.Visibility = string.IsNullOrEmpty(p.Copia) ? Visibility.Collapsed : Visibility.Visible;
            PixAutoDica.Visibility = Visibility.Visible;
            Esperar();
        }
        catch (Exception ex)
        {
            // sem o Pix automático agora: volta a chave Pix de sempre
            App.Log(ex);
            _pixPedido = false;
            PixAuto.Visibility = Visibility.Collapsed;
            PixBox.Visibility = string.IsNullOrWhiteSpace(chave) ? Visibility.Collapsed : Visibility.Visible;
            BloqStatus.Text = "Não consegui gerar o QR Code do Pix agora: " + (ex is InvalidOperationException ? ex.Message : "sem resposta do servidor.");
            BloqStatus.Visibility = Visibility.Visible;
        }
    }

    // Com o Pix na tela, confere sozinho se o pagamento caiu (a cada 10 s; depois de 20 min, a cada minuto)
    void Esperar()
    {
        if (_espera != null) return;
        _esperaDesde = DateTime.Now;
        _espera = new DispatcherTimer { Interval = TimeSpan.FromSeconds(10) };
        _espera.Tick += async (_, _) =>
        {
            if (!IsLoaded || BloqPanel.Visibility != Visibility.Visible) { _espera.Stop(); return; }
            if (DateTime.Now - _esperaDesde > TimeSpan.FromMinutes(20)) _espera.Interval = TimeSpan.FromMinutes(1);
            if (_ocupado || string.IsNullOrEmpty(Store.D.AcessoId)) return;
            var r = await Conta.VerificarAsync(Store.D.AcessoId);
            if (r.Estado != Conta.Estado.Liberado || !IsLoaded || BloqPanel.Visibility != Visibility.Visible || _ocupado) return;
            _espera.Stop();
            MainWindow.I.Toast("Pagamento confirmado! Bom proveito.");
            MainWindow.I.ContaLiberada(r);
        };
        Unloaded += (_, _) => _espera.Stop();
        _espera.Start();
    }

    void CopiarCodigo_Click(object sender, RoutedEventArgs e)
    {
        try { Clipboard.SetText(PixCopia.Text); MainWindow.I.Toast("Código Pix copiado. Cole no app do seu banco (Pix copia e cola)."); } catch { }
    }

    void Erro(string msg)
    {
        ErrorText.Text = msg;
        ErrorText.Visibility = Visibility.Visible;
    }

    void Senha_Changed(object sender, RoutedEventArgs e) =>
        SenhaHint.Visibility = SenhaBox.SecurePassword.Length == 0 ? Visibility.Visible : Visibility.Collapsed;

    void Field_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter) return;
        e.Handled = true;
        if (sender == LoginBox && SenhaBox.SecurePassword.Length == 0) SenhaBox.Focus();
        else Entrar();
    }

    void Entrar_Click(object sender, RoutedEventArgs e) => Entrar();

    async void Entrar()
    {
        if (_ocupado) return;
        var login = LoginBox.Text.Trim();
        var senha = SenhaBox.Password;
        if (login.Length == 0) { Erro("Digite o seu login."); LoginBox.Focus(); return; }
        if (senha.Length == 0) { Erro("Digite a sua senha."); SenhaBox.Focus(); return; }

        _ocupado = true;
        EntrarBtn.IsEnabled = false;
        EntrarText.Text = "Verificando...";
        ErrorText.Visibility = Visibility.Collapsed;
        var id = Conta.IdAcesso(login, senha);
        var r = await Conta.VerificarAsync(id);
        _ocupado = false;
        EntrarBtn.IsEnabled = true;
        EntrarText.Text = "Entrar";

        switch (r.Estado)
        {
            case Conta.Estado.Invalido:
                Erro("Login ou senha incorretos.");
                SenhaBox.Clear();
                SenhaBox.Focus();
                break;
            case Conta.Estado.SemConexao:
                Erro(r.Mensagem);
                break;
            default:
                // login e senha certos: guarda para entrar sozinho da próxima vez (mesmo bloqueado, para "verificar de novo")
                Store.D.Login = login.ToLowerInvariant();
                Store.D.AcessoId = id;
                Store.Save();
                if (r.Estado == Conta.Estado.Bloqueado) MostrarBloqueio(r);
                else MainWindow.I.ContaLiberada(r);
                break;
        }
    }

    async void DeNovo_Click(object sender, RoutedEventArgs e)
    {
        if (_ocupado || string.IsNullOrEmpty(Store.D.AcessoId)) return;
        _ocupado = true;
        DeNovoBtn.IsEnabled = false;
        DeNovoText.Text = "Verificando...";
        BloqStatus.Visibility = Visibility.Collapsed;
        var r = await Conta.VerificarAsync(Store.D.AcessoId);
        _ocupado = false;
        DeNovoBtn.IsEnabled = true;

        switch (r.Estado)
        {
            case Conta.Estado.Liberado:
                MainWindow.I.ContaLiberada(r);
                break;
            case Conta.Estado.Bloqueado:
                MostrarBloqueio(r);
                BloqStatus.Text = "Ainda não foi liberado. Assim que o pagamento for confirmado, tente de novo.";
                BloqStatus.Visibility = Visibility.Visible;
                break;
            case Conta.Estado.Invalido:
                MainWindow.I.SairDaConta("Sua senha mudou ou o acesso foi removido. Entre de novo.");
                break;
            default:
                DeNovoText.Text = "Verificar de novo";
                BloqStatus.Text = r.Mensagem;
                BloqStatus.Visibility = Visibility.Visible;
                break;
        }
    }

    void CopiarPix_Click(object sender, RoutedEventArgs e)
    {
        try { Clipboard.SetText(PixText.Text); MainWindow.I.Toast("Chave Pix copiada"); } catch { }
    }

    void OutraConta_Click(object sender, RoutedEventArgs e) => MainWindow.I.SairDaConta(null);
}
