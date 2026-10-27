using System.Globalization;
using System.Net.Http;
using System.Xml;

namespace StreamPLAY;

public sealed record Programa(DateTime Inicio, DateTime Fim, string Titulo, string Descricao)
{
    public string Horario => $"{Inicio:HH:mm}–{Fim:HH:mm}";
    public double Progresso(DateTime agora) =>
        Fim <= Inicio ? 0 : Math.Clamp((agora - Inicio).TotalSeconds / (Fim - Inicio).TotalSeconds, 0, 1);
}

/// <summary>
/// Guia de programação (EPG) dos canais ao vivo. A lista diz de onde vem o guia (#EXTM3U url-tvg="...") e cada
/// canal diz o seu código nele (tvg-id). O arquivo XMLTV é baixado direto da fonte (não passa pelo Firebase da
/// lista), fica salvo no PC e é renovado a cada 6 horas; dele só se guarda a programação dos canais da lista,
/// das últimas horas até as próximas 36 horas.
/// </summary>
public static class Guia
{
    static readonly TimeSpan Validade = TimeSpan.FromHours(6);
    static readonly HttpClient Http = CriarHttp();
    static HttpClient CriarHttp()
    {
        var h = new HttpClient(new SocketsHttpHandler { AutomaticDecompression = System.Net.DecompressionMethods.All }) { Timeout = TimeSpan.FromSeconds(90) };
        h.DefaultRequestHeaders.UserAgent.ParseAdd("StreamPLAY/1.0");
        return h;
    }

    static readonly string Pasta = Environment.GetEnvironmentVariable("STREAMPLAY_DATA") is { Length: > 0 }
        ? Path.Combine(Store.Dir, "guia")
        : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "StreamPLAY", "guia");

    static Dictionary<string, List<Programa>> _grade = new(StringComparer.OrdinalIgnoreCase);
    static int _versao;

    /// <summary>Avisa (na thread da tela) quando a programação foi carregada ou atualizada.</summary>
    public static event Action Mudou;

    public static bool Tem(Item canal) => canal?.TvgId != null && _grade.ContainsKey(canal.TvgId);

    /// <summary>O programa que está passando agora e o próximo (qualquer um pode ser null).</summary>
    public static (Programa agora, Programa seguinte) AgoraESeguir(Item canal, DateTime agora)
    {
        if (canal?.TvgId == null || !_grade.TryGetValue(canal.TvgId, out var lista)) return (null, null);
        for (int i = 0; i < lista.Count; i++)
        {
            if (lista[i].Fim <= agora) continue;
            if (lista[i].Inicio <= agora) return (lista[i], i + 1 < lista.Count ? lista[i + 1] : null);
            return (null, lista[i]);   // buraco na grade: nada agora, mas tem o próximo
        }
        return (null, null);
    }

    /// <summary>Carrega o guia para os canais desta lista (em segundo plano). Pode ser chamado de novo a qualquer hora.</summary>
    public static async Task CarregarAsync(Library lib)
    {
        int versao = ++_versao;
        var ids = new HashSet<string>(lib.AllChannels.Select(c => c.TvgId).Where(i => i != null), StringComparer.OrdinalIgnoreCase);
        var fontes = lib.Guias.ToList();
        Dictionary<string, List<Programa>> grade = new(StringComparer.OrdinalIgnoreCase);
        if (ids.Count > 0 && fontes.Count > 0)
        {
            try
            {
                grade = await Task.Run(async () =>
                {
                    var g = new Dictionary<string, List<Programa>>(StringComparer.OrdinalIgnoreCase);
                    foreach (var url in fontes)
                    {
                        var arquivo = await Obter(url);
                        if (arquivo != null) Ler(arquivo, ids, g);
                    }
                    foreach (var l in g.Values) l.Sort((x, y) => x.Inicio.CompareTo(y.Inicio));
                    return g;
                });
            }
            catch (Exception ex) { App.Log(ex); return; }
        }
        if (versao != _versao) return;   // chegou uma lista mais nova enquanto lia
        _grade = grade;
        Mudou?.Invoke();
    }

    // Caminho do guia salvo no PC: baixa de novo se passou da validade (ou se não tem). Sem internet, usa o salvo.
    static async Task<string> Obter(string url)
    {
        Directory.CreateDirectory(Pasta);
        var arquivo = Path.Combine(Pasta, M3U.Hash(url) + ".xml");
        var info = new FileInfo(arquivo);
        if (info.Exists && DateTime.Now - info.LastWriteTime < Validade) return arquivo;
        try
        {
            var bytes = await Http.GetByteArrayAsync(url);
            if (bytes.Length > 2 && bytes[0] == 0x1F && bytes[1] == 0x8B)   // guia publicado compactado (.xml.gz)
            {
                using var gz = new System.IO.Compression.GZipStream(new MemoryStream(bytes), System.IO.Compression.CompressionMode.Decompress);
                using var ms = new MemoryStream();
                await gz.CopyToAsync(ms);
                bytes = ms.ToArray();
            }
            var tmp = arquivo + ".tmp";
            await File.WriteAllBytesAsync(tmp, bytes);
            File.Move(tmp, arquivo, true);
            return arquivo;
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or IOException)
        {
            return info.Exists ? arquivo : null;
        }
    }

    // Lê o XMLTV sem carregar tudo na memória, guardando só os canais pedidos e a janela de tempo útil.
    static void Ler(string arquivo, HashSet<string> ids, Dictionary<string, List<Programa>> grade)
    {
        var agora = DateTime.Now;
        var de = agora.AddHours(-3);
        var ate = agora.AddHours(36);
        var cfg = new XmlReaderSettings { DtdProcessing = DtdProcessing.Ignore, IgnoreComments = true, IgnoreWhitespace = true };
        using var r = XmlReader.Create(arquivo, cfg);
        while (r.ReadToFollowing("programme"))
        {
            var canal = r.GetAttribute("channel");
            if (canal == null || !ids.Contains(canal)) continue;
            if (!Hora(r.GetAttribute("start"), out var inicio) || !Hora(r.GetAttribute("stop"), out var fim)) continue;
            if (fim < de || inicio > ate) continue;
            string titulo = null, desc = null;
            using (var sub = r.ReadSubtree())
            {
                while (sub.Read())
                {
                    if (sub.NodeType != XmlNodeType.Element) continue;
                    if (sub.Name == "title" && titulo == null) titulo = sub.ReadElementContentAsString();
                    else if (sub.Name == "desc" && desc == null) desc = sub.ReadElementContentAsString();
                }
            }
            if (string.IsNullOrWhiteSpace(titulo)) continue;
            if (!grade.TryGetValue(canal, out var lista)) grade[canal] = lista = new List<Programa>();
            lista.Add(new Programa(inicio, fim, titulo.Trim(), string.IsNullOrWhiteSpace(desc) ? null : desc.Trim()));
        }
    }

    // XMLTV: "20261026190000 +0000" (o fuso é opcional). Devolve no horário deste PC.
    static bool Hora(string s, out DateTime local)
    {
        local = default;
        if (string.IsNullOrWhiteSpace(s) || s.Length < 14) return false;
        if (!DateTime.TryParseExact(s[..14], "yyyyMMddHHmmss", CultureInfo.InvariantCulture, DateTimeStyles.None, out var d)) return false;
        var fuso = s.Length > 14 ? s[14..].Trim() : "";
        var offset = TimeSpan.Zero;
        if (fuso.Length == 5 && (fuso[0] == '+' || fuso[0] == '-')
            && int.TryParse(fuso.AsSpan(1, 2), out var hh) && int.TryParse(fuso.AsSpan(3, 2), out var mm))
            offset = new TimeSpan(hh, mm, 0) * (fuso[0] == '-' ? -1 : 1);
        local = new DateTimeOffset(d, offset).LocalDateTime;
        return true;
    }
}
