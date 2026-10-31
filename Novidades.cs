using System.Globalization;
using System.Text.Json;

namespace StreamPLAY;

/// <summary>
/// Novidades que o dono escreve no PLAYGER (aba Novidades). O site mostra todas; o app mostra cada novidade nova uma vez,
/// num popup ao abrir (já na tela da lista). Ficam em novidades/{id} no Firestore, que qualquer um pode ler.
/// Só entram as dos últimos 45 dias (quem instala agora não recebe as antigas), no máximo 3 por vez.
/// </summary>
public static class Novidades
{
    public record Novidade(string Id, string Titulo, string Texto, DateTime Data);

    const int Dias = 45, Max = 3;
    public const string Site = "https://streamplay-site.web.app/#novidades";

    public static async Task<List<Novidade>> NovasAsync(CancellationToken ct = default)
    {
        var raiz = Conta.Base[..Conta.Base.TrimEnd('/').LastIndexOf('/')];   // .../documents
        using var resp = await Conta.Http.GetAsync(raiz + "/novidades?pageSize=100&key=" + Conta.ChaveApi, ct);
        if (!resp.IsSuccessStatusCode) return new();
        var vistas = Store.D.NovidadesVistas ?? new List<string>();
        var desde = DateTime.Today.AddDays(-Dias);
        return Ler(await resp.Content.ReadAsStringAsync(ct))
            .Where(n => !vistas.Contains(n.Id) && n.Data >= desde)
            .OrderByDescending(n => n.Data).ThenByDescending(n => n.Id, StringComparer.Ordinal)
            .Take(Max).ToList();
    }

    public static void MarcarVistas(IEnumerable<Novidade> l)
    {
        var v = Store.D.NovidadesVistas ??= new List<string>();
        foreach (var n in l) if (!v.Contains(n.Id)) v.Add(n.Id);
        while (v.Count > 300) v.RemoveAt(0);
        Store.Save();
    }

    static List<Novidade> Ler(string json)
    {
        var l = new List<Novidade>();
        using var doc = JsonDocument.Parse(json);
        if (!doc.RootElement.TryGetProperty("documents", out var docs)) return l;
        foreach (var d in docs.EnumerateArray())
        {
            if (!d.TryGetProperty("fields", out var f)) continue;
            string T(string n) => f.TryGetProperty(n, out var v) && v.TryGetProperty("stringValue", out var s) ? s.GetString() : null;
            var nome = d.GetProperty("name").GetString();
            if (string.IsNullOrWhiteSpace(T("titulo")) || !DateTime.TryParseExact(T("data"), "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var data)) continue;
            l.Add(new Novidade(nome[(nome.LastIndexOf('/') + 1)..], T("titulo").Trim(), (T("texto") ?? "").Trim(), data));
        }
        return l;
    }
}
