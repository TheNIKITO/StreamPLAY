using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace StreamPLAY;

/// <summary>
/// Busca com IA (botão ✨). O servidor da lista no Cloudflare pergunta à IA o que procurar (palavras-chave do TMDB,
/// termos em português, gêneros e títulos) e o app cruza isso com a lista: só aparece o que existe nela. As
/// palavras-chave de cada título vêm do campo palavras= que o buscar-palavras-chave.bat coloca na lista.
/// </summary>
public static class Ia
{
    // STREAMPLAY_IA_URL só existe para testes (servidor local com uma IA falsa)
    static readonly string Url = Environment.GetEnvironmentVariable("STREAMPLAY_IA_URL") is { Length: > 0 } t
        ? t : "https://streamplay-lista.streamplay-pix.workers.dev/ia";
    static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(60) };
    const int MaxResultados = 120;

    public sealed class Resposta
    {
        [JsonPropertyName("frase")] public string Frase { get; set; }
        [JsonPropertyName("tipo")] public string Tipo { get; set; }
        [JsonPropertyName("palavras_chave")] public string[] PalavrasChave { get; set; } = [];
        [JsonPropertyName("termos")] public string[] Termos { get; set; } = [];
        [JsonPropertyName("generos")] public string[] Generos { get; set; } = [];
        [JsonPropertyName("titulos")] public string[] Titulos { get; set; } = [];
        [JsonPropertyName("erro")] public string Erro { get; set; }
    }

    /// <summary>Erro que pode ser mostrado como está (vem do servidor ou é falta de conexão).</summary>
    public sealed class IaErro(string msg) : Exception(msg);

    public static async Task<Resposta> PerguntarAsync(string pergunta, CancellationToken ct = default)
    {
        var corpo = new StringContent(JsonSerializer.Serialize(new { pergunta, aparelho = Store.MeuAparelho() }), Encoding.UTF8, "application/json");
        HttpResponseMessage r;
        try { r = await Http.PostAsync(Url, corpo, ct); }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
        catch (Exception) { throw new IaErro("Sem conexão com a IA. Confira a internet e tente de novo."); }
        using (r)
        {
            Resposta resp = null;
            try { resp = JsonSerializer.Deserialize<Resposta>(await r.Content.ReadAsStringAsync(ct)); } catch (JsonException) { }
            if (!r.IsSuccessStatusCode || resp == null)
                throw new IaErro(resp?.Erro ?? "A IA não respondeu. Tente de novo.");
            return resp;
        }
    }

    // ---------- cruzar a resposta com a lista ----------

    static readonly Regex Colchetes = new(@"\[[^\]]*\]|\([^)]*\)", RegexOptions.Compiled);
    static readonly Regex NaoLetra = new(@"[^A-Z0-9]+", RegexOptions.Compiled);
    static readonly HashSet<string> Etiquetas = ["4K", "UHD", "HDR", "FHD", "HD", "SD", "DUB", "LEG", "DUBLADO", "LEGENDADO", "NACIONAL", "LANCAMENTO"];

    /// <summary>Maiúsculas, sem acento nem pontuação, com espaço nas pontas (para achar palavras inteiras).</summary>
    static string Limpo(string s) => " " + NaoLetra.Replace(M3U.Norm(s), " ").Trim() + " ";

    /// <summary>O nome sem ano, [L], 4K etc.: "Moana 2 4K [L]" e "Moana 2" viram o mesmo título.</summary>
    static string NomeBase(string nome)
    {
        var w = Limpo(Colchetes.Replace(nome ?? "", " ")).Split(' ', StringSplitOptions.RemoveEmptyEntries).Where(x => !Etiquetas.Contains(x));
        return " " + string.Join(' ', w) + " ";
    }

    public static List<Item> Achar(Resposta r, IEnumerable<Item> pool)
    {
        if (r.Tipo == "filme") pool = pool.Where(i => i.Kind == Kind.Movie);
        else if (r.Tipo == "serie") pool = pool.Where(i => i.Kind == Kind.Series);
        var titulos = r.Titulos.Select(NomeBase).Where(t => t.Trim().Length > 0).ToList();
        var palavras = new HashSet<string>(r.PalavrasChave.Select(p => p.Trim().ToLowerInvariant()).Where(p => p.Length > 0));
        var parciais = palavras.Where(p => p.Length >= 4).Select(p => " " + p + " ").ToList();
        var termos = r.Termos.Select(Limpo).Where(t => t.Trim().Length >= 3).Distinct().ToList();
        var generos = r.Generos.Select(M3U.Norm).Where(g => g.Length > 0).ToList();

        var achados = new List<(Item item, int pontos, string nome)>();
        foreach (var item in pool)
        {
            string nome = NomeBase(item.Name);
            int pontos = 0;
            // 1) um dos títulos que a IA citou (o primeiro vale mais; nome igual vale mais que "começa com")
            for (int j = 0; j < titulos.Count; j++)
            {
                var t = titulos[j];
                int p = nome == t ? 100 - j : nome.StartsWith(t) ? 70 - j : t.Length >= 8 && nome.Contains(t) ? 40 - j : 0;
                if (p > pontos) pontos = p;
            }
            // 2) palavras-chave do TMDB: igual vale 12; "racing" dentro de "street racing" vale 5
            if (item.Keywords != null)
                foreach (var k in item.Keywords)
                {
                    var kl = k.ToLowerInvariant();
                    if (palavras.Contains(kl)) pontos += 12;
                    else if (parciais.Any(p => (" " + kl + " ").Contains(p))) pontos += 5;
                }
            // 3) termos em português no nome ou na descrição (quem não tem palavras-chave entra com 2 termos achados)
            int termosAchados = 0;
            if (termos.Count > 0)
            {
                string plot = item.Plot == null ? "" : Limpo(item.Plot);
                foreach (var t in termos)
                {
                    int p = nome.Contains(t) ? 6 : plot.Contains(t) ? 3 : 0;
                    if (p > 0) { pontos += p; termosAchados++; }
                }
            }
            // 4) gênero só desempata
            if (pontos > 0 && item.Genre != null && generos.Any(g => M3U.Norm(item.Genre).Contains(g))) pontos += 2;
            if (pontos >= 10 || termosAchados >= 2) achados.Add((item, pontos, nome));
        }
        return achados
            .OrderByDescending(a => a.pontos)
            .ThenByDescending(a => double.TryParse(a.item.Rating, System.Globalization.NumberStyles.Float,
                System.Globalization.CultureInfo.InvariantCulture, out var n) ? n : 0)
            .DistinctBy(a => (a.item.Kind, a.nome))
            .Take(MaxResultados)
            .Select(a => a.item)
            .ToList();
    }
}
