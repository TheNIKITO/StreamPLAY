using System.Globalization;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace StreamPLAY;

/// <summary>
/// Login dos clientes pelo painel PLAYGER (Firebase). Para cada cliente, o painel grava no Firestore o documento
/// acessos/{sha256("playger-acesso|login|senha")}. O app só encontra o documento sabendo login e senha, e o
/// documento diz se a conta está ativa e a partir de que dia o acesso bloqueia por falta de pagamento — assim o
/// bloqueio acontece no dia certo mesmo que o painel fique dias sem ser aberto. A data usada é a do servidor.
/// </summary>
public static class Conta
{
    const string Projeto = "playger-svect";
    const string ChaveApi = "AIzaSyDhFrw8OFoCfSqptvuW1irLmpPgjegBLSo"; // chave pública do app web do Firebase (não é segredo)

    // STREAMPLAY_PLAYGER_URL só existe para testes (servidor local que responde como a API REST do Firestore)
    static readonly string TestUrl = Environment.GetEnvironmentVariable("STREAMPLAY_PLAYGER_URL");
    static readonly string Base = string.IsNullOrEmpty(TestUrl)
        ? $"https://firestore.googleapis.com/v1/projects/{Projeto}/databases/(default)/documents/acessos/"
        : TestUrl.TrimEnd('/') + "/";
    static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(15) };
    static readonly CultureInfo Br = new("pt-BR");

    public enum Estado { Liberado, Bloqueado, Invalido, SemConexao }

    public class Acesso
    {
        public string Login { get; init; }
        public string Nome { get; init; }
        public string Plano { get; init; }
        public string Negocio { get; init; }
        public string Pix { get; init; }
        public bool Ativo { get; init; }
        public bool Manual { get; init; }
        public DateTime? BloqueiaEm { get; init; }
        public DateTime? LiberadoAte { get; init; }
        public DateTime? FaturaVenc { get; init; }
        public double? FaturaValor { get; init; }
    }

    public record Resultado(Estado Estado, string Titulo, string Mensagem, Acesso Acesso, DateTime Hoje);

    public static string IdAcesso(string login, string senha) =>
        Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes("playger-acesso|" + (login ?? "").Trim().ToLowerInvariant() + "|" + senha)));

    public static async Task<Resultado> VerificarAsync(string id, CancellationToken ct = default)
    {
        HttpResponseMessage resp;
        try { resp = await Http.GetAsync(Base + id + "?key=" + ChaveApi, ct); }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
        {
            return new(Estado.SemConexao, "Sem conexão", "Não consegui falar com o servidor. Verifique a internet e tente de novo.", null, DateTime.Today);
        }
        using (resp)
        {
            var hoje = resp.Headers.Date?.ToLocalTime().Date ?? DateTime.Today;
            if (resp.StatusCode == HttpStatusCode.NotFound)
                return new(Estado.Invalido, "Login ou senha incorretos", "Confira o login e a senha que você recebeu.", null, hoje);
            if (!resp.IsSuccessStatusCode)
                return new(Estado.SemConexao, "Servidor indisponível", $"O servidor não respondeu direito (erro {(int)resp.StatusCode}). Tente de novo daqui a pouco.", null, hoje);
            try { return Avaliar(Ler(await resp.Content.ReadAsStringAsync(ct)), hoje); }
            catch (Exception ex)
            {
                App.Log(ex);
                return new(Estado.SemConexao, "Resposta inválida", "Não entendi a resposta do servidor. Tente de novo daqui a pouco.", null, hoje);
            }
        }
    }

    /// <summary>Mesma regra do painel: desativado, bloqueio manual ou fatura vencida além da tolerância.</summary>
    public static Resultado Avaliar(Acesso a, DateTime hoje)
    {
        var contato = string.IsNullOrWhiteSpace(a.Negocio) ? "o suporte" : a.Negocio;
        if (!a.Ativo) return new(Estado.Bloqueado, "Conta desativada", $"Esta conta está desativada. Fale com {contato}.", a, hoje);
        if (a.Manual) return new(Estado.Bloqueado, "Acesso bloqueado", $"Seu acesso está bloqueado. Fale com {contato}.", a, hoje);
        bool liberado = a.LiberadoAte is { } l && l >= hoje;
        if (a.BloqueiaEm is { } b && hoje >= b && !liberado)
        {
            var msg = (a.FaturaVenc is { } v ? $"Sua fatura de {Dinheiro(a.FaturaValor)} venceu em {v:dd/MM/yyyy}." : "Há uma fatura em aberto.")
                      + " Assim que o pagamento for confirmado, o acesso volta na hora.";
            return new(Estado.Bloqueado, "Acesso bloqueado por falta de pagamento", msg, a, hoje);
        }
        return new(Estado.Liberado, "", "", a, hoje);
    }

    /// <summary>Lembrete de vencimento para mostrar depois de entrar (ou null).</summary>
    public static string Aviso(Resultado r)
    {
        if (r?.Acesso?.FaturaVenc is not { } v) return null;
        var valor = Dinheiro(r.Acesso.FaturaValor);
        int d = (v - r.Hoje).Days;
        if (d < 0) return $"Sua fatura de {valor} venceu em {v:dd/MM}. Pague para não perder o acesso.";
        if (d == 0) return $"Sua fatura de {valor} vence hoje.";
        if (d <= 3) return $"Sua fatura de {valor} vence em {d} dia{(d > 1 ? "s" : "")} ({v:dd/MM}).";
        return null;
    }

    public static string Dinheiro(double? v) => (v ?? 0).ToString("C", Br);

    // ---------- leitura do documento no formato da API REST do Firestore ----------

    static Acesso Ler(string json)
    {
        using var doc = JsonDocument.Parse(json);
        var f = doc.RootElement.GetProperty("fields");
        JsonElement? fatura = Campo(f, "fatura") is { } fx && fx.TryGetProperty("mapValue", out var mv) && mv.TryGetProperty("fields", out var ff) ? ff : null;
        return new Acesso
        {
            Login = Texto(f, "login"),
            Nome = Texto(f, "nome"),
            Plano = Texto(f, "plano"),
            Negocio = Texto(f, "negocio"),
            Pix = Texto(f, "pix"),
            Ativo = Bool(f, "ativo"),
            Manual = Bool(f, "manual"),
            BloqueiaEm = Data(f, "bloqueiaEm"),
            LiberadoAte = Data(f, "liberadoAte"),
            FaturaVenc = fatura is { } fa ? Data(fa, "venc") : null,
            FaturaValor = fatura is { } fb ? Numero(fb, "valor") : null,
        };
    }

    static JsonElement? Campo(JsonElement f, string nome) => f.TryGetProperty(nome, out var v) ? v : null;
    static string Texto(JsonElement f, string n) => Campo(f, n) is { } v && v.TryGetProperty("stringValue", out var s) ? s.GetString() : null;
    static bool Bool(JsonElement f, string n) => Campo(f, n) is { } v && v.TryGetProperty("booleanValue", out var b) && b.GetBoolean();
    static DateTime? Data(JsonElement f, string n) =>
        DateTime.TryParseExact(Texto(f, n), "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var d) ? d : null;
    static double? Numero(JsonElement f, string n)
    {
        if (Campo(f, n) is not { } v) return null;
        if (v.TryGetProperty("doubleValue", out var d)) return d.GetDouble();
        if (v.TryGetProperty("integerValue", out var i) && double.TryParse(i.GetString(), NumberStyles.Any, CultureInfo.InvariantCulture, out var x)) return x;
        return null;
    }
}
