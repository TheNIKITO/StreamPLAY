using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Input;

namespace StreamPLAY;

/// <summary>Tela de entrada com a conta do PLAYGER (e o aviso de acesso bloqueado).</summary>
public partial class LoginView : UserControl
{
    bool _ocupado;

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
