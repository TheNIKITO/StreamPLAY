using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;

namespace StreamPLAY;

/// <summary>
/// Sincroniza o "continuar assistindo" e a Minha lista entre os aparelhos da mesma conta (PC, TV, celular) num
/// documento só no Firestore: acessos/{id}/sync/dados = { p: {chave: {pos, dur, at, show}}, f: {chave: {on, at}}, l, at }
/// (l = quando alguém usou "Limpar continuar assistindo": progresso mais velho que isso some em todos os aparelhos).
/// Cada lado junta o que tem com o que está na nuvem — vale o mais recente de cada item — e grava de volta.
/// "at" de cada item = milissegundos UTC. Falhas são ignoradas: o aparelho fica com o que tem e tenta de novo depois.
/// Igual ao Sincronia.kt do Android.
/// </summary>
public static class Sincronia
{
    const int MaxProgresso = 40;   // na nuvem só os mais recentes (o aparelho guarda todos)
    const int MaxFavoritos = 300;
    static readonly SemaphoreSlim Um = new(1, 1);

    /// <summary>Mudou progresso ou Minha lista aqui e a nuvem ainda não tem.</summary>
    public static bool Sujo { get; set; }

    record Prog(long Pos, long Dur, long At, string Show);
    record Fav(bool On, long At);

    static long Ms(DateTime t) => new DateTimeOffset(t).ToUnixTimeMilliseconds();
    static DateTime Hora(long ms) => DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime;

    /// <summary>Lê a nuvem e junta com o que está aqui. Devolve true se algo mudou neste aparelho (a tela atualiza).</summary>
    public static async Task<bool> BaixarAsync(string id, CancellationToken ct = default)
    {
        if (string.IsNullOrEmpty(id)) return false;
        await Um.WaitAsync(ct);
        try
        {
            var nuvem = await Ler(id, ct);
            return nuvem != null && Juntar(nuvem.Value.p, nuvem.Value.f, nuvem.Value.l);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException or KeyNotFoundException or InvalidOperationException) { return false; }
        finally { Um.Release(); }
    }

    /// <summary>Junta com a nuvem (para não apagar o que outro aparelho mandou) e grava. Só faz algo se estiver Sujo.</summary>
    public static async Task<bool> EnviarAsync(string id, CancellationToken ct = default)
    {
        if (string.IsNullOrEmpty(id) || !Sujo) return false;
        await Um.WaitAsync(ct);
        try
        {
            Sujo = false;
            var nuvem = await Ler(id, ct);
            if (nuvem == null) { Sujo = true; return false; }
            bool mudou = Juntar(nuvem.Value.p, nuvem.Value.f, nuvem.Value.l);
            Sujo = false;   // a junção pode marcar "falta subir": é o que vai agora (mudança que chegar durante a gravação marca de novo)
            if (!await Gravar(id, ct)) Sujo = true;
            return mudou;
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException or KeyNotFoundException or InvalidOperationException)
        {
            Sujo = true;
            return false;
        }
        finally { Um.Release(); }
    }

    // null = não deu para ler; listas vazias = ainda não existe nada na nuvem
    static async Task<(Dictionary<string, Prog> p, Dictionary<string, Fav> f, long l)?> Ler(string id, CancellationToken ct)
    {
        using var resp = await Conta.Http.GetAsync(Conta.Base + id + "/sync/dados?key=" + Conta.ChaveApi, ct);
        if (resp.StatusCode == HttpStatusCode.NotFound) return (new(), new(), 0);
        if (!resp.IsSuccessStatusCode) return null;
        using var doc = JsonDocument.Parse(await resp.Content.ReadAsStringAsync(ct));
        var p = new Dictionary<string, Prog>();
        var f = new Dictionary<string, Fav>();
        if (!doc.RootElement.TryGetProperty("fields", out var campos)) return (p, f, 0);
        foreach (var (k, v) in Mapa(campos, "p"))
            p[k] = new Prog(Num(v, "pos"), Num(v, "dur"), Num(v, "at"), Txt(v, "show"));
        foreach (var (k, v) in Mapa(campos, "f"))
            f[k] = new Fav(v.TryGetProperty("on", out var on) && on.TryGetProperty("booleanValue", out var b) && b.GetBoolean(), Num(v, "at"));
        return (p, f, Num(campos, "l"));
    }

    static IEnumerable<(string, JsonElement)> Mapa(JsonElement campos, string nome)
    {
        if (!campos.TryGetProperty(nome, out var m) || !m.TryGetProperty("mapValue", out var mv) || !mv.TryGetProperty("fields", out var itens)) yield break;
        foreach (var it in itens.EnumerateObject())
            if (it.Value.TryGetProperty("mapValue", out var iv) && iv.TryGetProperty("fields", out var f)) yield return (it.Name, f);
    }

    static long Num(JsonElement f, string n) =>
        f.TryGetProperty(n, out var v) && v.TryGetProperty("integerValue", out var i) && long.TryParse(i.GetString(), out var x) ? x : 0;
    static string Txt(JsonElement f, string n) =>
        f.TryGetProperty(n, out var v) && v.TryGetProperty("stringValue", out var s) ? s.GetString() : null;

    // Junta a nuvem no Store (vale o mais recente de cada item). Devolve true se algo daqui mudou.
    static bool Juntar(Dictionary<string, Prog> p, Dictionary<string, Fav> f, long limpo)
    {
        var d = Store.D;
        bool mudou = false;
        if (limpo > d.ProgressoLimpo)
        {
            d.ProgressoLimpo = limpo;
            foreach (var k in d.Progress.Where(kv => Ms(kv.Value.At) <= limpo).Select(kv => kv.Key).ToList()) d.Progress.Remove(k);
            mudou = true;
        }
        else if (limpo < d.ProgressoLimpo) Sujo = true;
        foreach (var (k, c) in p)
        {
            if (c.At <= d.ProgressoLimpo) continue;
            var aqui = Store.GetProgress(k);
            if (aqui != null && Ms(aqui.At) >= c.At) { if (Ms(aqui.At) > c.At) Sujo = true; continue; }
            if (c.Dur <= 0) continue;
            d.Progress[k] = new Progress { Pos = c.Pos, Dur = c.Dur, At = Hora(c.At), Show = c.Show };
            mudou = true;
        }
        // tem aqui e não tem lá (ou lá é mais velho): a nuvem precisa receber
        if (d.Progress.OrderByDescending(kv => kv.Value.At).Take(MaxProgresso).Any(kv => !p.ContainsKey(kv.Key))) Sujo = true;

        foreach (var (k, c) in f)
        {
            d.FavLog.TryGetValue(k, out var aqui);
            if (aqui != null && aqui.At >= c.At) { if (aqui.At > c.At) Sujo = true; continue; }
            d.FavLog[k] = new FavMarca { On = c.On, At = c.At };
            bool tem = d.Favorites.Contains(k);
            if (c.On && !tem) { d.Favorites.Insert(0, k); mudou = true; }
            else if (!c.On && tem) { d.Favorites.Remove(k); mudou = true; }
        }
        // favoritos daqui que a nuvem não conhece (inclusive os de antes da sincronização existir)
        foreach (var k in d.Favorites) if (!f.ContainsKey(k)) { Sujo = true; break; }
        foreach (var k in d.FavLog.Keys) if (!f.ContainsKey(k)) { Sujo = true; break; }
        if (mudou) Store.Save();
        return mudou;
    }

    static async Task<bool> Gravar(string id, CancellationToken ct)
    {
        var d = Store.D;
        var p = new Dictionary<string, object>();
        foreach (var (k, v) in d.Progress.OrderByDescending(kv => kv.Value.At).Take(MaxProgresso))
        {
            var c = new Dictionary<string, object>
            {
                ["pos"] = new { integerValue = v.Pos.ToString() },
                ["dur"] = new { integerValue = v.Dur.ToString() },
                ["at"] = new { integerValue = Ms(v.At).ToString() },
            };
            if (!string.IsNullOrEmpty(v.Show)) c["show"] = new { stringValue = v.Show };
            p[k] = new { mapValue = new { fields = c } };
        }
        // favoritos: os ligados (sem marca de hora = de antes da sincronização, valem como antigos) + os tirados mais recentes
        var marcas = d.FavLog.ToDictionary(kv => kv.Key, kv => kv.Value);
        foreach (var k in d.Favorites) if (!marcas.ContainsKey(k)) marcas[k] = new FavMarca { On = true, At = 1 };
        var f = new Dictionary<string, object>();
        foreach (var (k, v) in marcas.OrderByDescending(kv => kv.Value.On).ThenByDescending(kv => kv.Value.At).Take(MaxFavoritos))
            f[k] = new { mapValue = new { fields = new { on = new { booleanValue = v.On }, at = new { integerValue = v.At.ToString() } } } };

        var raiz = Conta.Base[..Conta.Base.TrimEnd('/').LastIndexOf('/')];   // .../documents
        var corpo = JsonSerializer.Serialize(new
        {
            writes = new object[]
            {
                new Dictionary<string, object>
                {
                    ["update"] = new
                    {
                        name = $"projects/{Conta.Projeto}/databases/(default)/documents/acessos/{id}/sync/dados",
                        fields = new Dictionary<string, object>
                        {
                            ["p"] = new { mapValue = new { fields = p } },
                            ["f"] = new { mapValue = new { fields = f } },
                            ["l"] = new { integerValue = d.ProgressoLimpo.ToString() },
                        },
                    },
                    ["updateTransforms"] = new[] { new { fieldPath = "at", setToServerValue = "REQUEST_TIME" } },
                },
            },
        });
        using var resp = await Conta.Http.PostAsync(raiz + ":commit?key=" + Conta.ChaveApi, new StringContent(corpo, Encoding.UTF8, "application/json"), ct);
        return resp.IsSuccessStatusCode;
    }
}
