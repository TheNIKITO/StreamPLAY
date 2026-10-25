using System.Diagnostics;
using System.Net.Http;
using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json;

namespace StreamPLAY;

/// <summary>
/// Atualização automática pelas Releases do GitHub (mesma ideia do ScreenPLAY): procura uma versão nova,
/// baixa o StreamPLAY.exe da release e troca o executável. O Windows deixa renomear um .exe em uso,
/// então o atual vira StreamPLAY.exe.old, o novo entra no lugar e o .old é apagado na próxima abertura.
/// </summary>
public static class Updater
{
    const string Repo = "TheNIKITO/StreamPLAY";
    public const string ReleasesPage = "https://github.com/" + Repo + "/releases/latest";

    // (antes do HttpClient: os campos estáticos são inicializados na ordem em que aparecem)
    public static Version Current { get; } = Normalize(Assembly.GetExecutingAssembly().GetName().Version ?? new Version(0, 0));
    public static string CurrentText => Current.ToString(3);

    // STREAMPLAY_UPDATE_URL só existe para testes locais (aponta para um JSON no formato da API do GitHub)
    static readonly string TestUrl = Environment.GetEnvironmentVariable("STREAMPLAY_UPDATE_URL");
    static readonly string Api = string.IsNullOrEmpty(TestUrl) ? $"https://api.github.com/repos/{Repo}/releases/latest" : TestUrl;
    static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "StreamPLAY", "atualizacao");

    static readonly HttpClient Http = CreateHttp();
    static HttpClient CreateHttp()
    {
        var h = new HttpClient { Timeout = Timeout.InfiniteTimeSpan };
        h.DefaultRequestHeaders.UserAgent.ParseAdd($"StreamPLAY-Updater/{CurrentText}");
        return h;
    }

    public record Release(Version Version, string Url, long Size, string Sha256);

    static string ExePath => Environment.ProcessPath;

    /// <summary>Só atualiza o exe publicado (arquivo único) — ou em teste, com STREAMPLAY_UPDATE_URL.</summary>
    public static bool Enabled => !string.IsNullOrEmpty(TestUrl) || string.IsNullOrEmpty(typeof(Updater).Assembly.Location);

    /// <summary>Versão já baixada e pronta para instalar (ou null).</summary>
    public static Release Ready { get; private set; }
    static string _readyFile;

    public static async Task<Release> CheckAsync(CancellationToken ct = default)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, Api);
        req.Headers.Accept.ParseAdd("application/vnd.github+json");
        using var resp = await Http.SendAsync(req, ct);
        if (!resp.IsSuccessStatusCode) return null;
        using var doc = JsonDocument.Parse(await resp.Content.ReadAsStringAsync(ct));
        var root = doc.RootElement;
        if (Bool(root, "draft") || Bool(root, "prerelease")) return null;
        var tag = root.TryGetProperty("tag_name", out var t) ? t.GetString() ?? "" : "";
        if (!Version.TryParse(tag.TrimStart('v', 'V'), out var v) || Normalize(v) <= Current) return null;
        if (!root.TryGetProperty("assets", out var assets)) return null;
        foreach (var a in assets.EnumerateArray())
        {
            var name = a.GetProperty("name").GetString() ?? "";
            if (!name.Equals("StreamPLAY.exe", StringComparison.OrdinalIgnoreCase)) continue;
            var digest = a.TryGetProperty("digest", out var d) && d.ValueKind == JsonValueKind.String ? d.GetString() : null;
            return new Release(Normalize(v), a.GetProperty("browser_download_url").GetString(), a.GetProperty("size").GetInt64(),
                digest?.StartsWith("sha256:", StringComparison.OrdinalIgnoreCase) == true ? digest[7..].ToLowerInvariant() : null);
        }
        return null;
    }

    /// <summary>Baixa a versão nova (com progresso de 0 a 1) e confere tamanho e SHA-256.</summary>
    public static async Task DownloadAsync(Release r, IProgress<double> progress = null, CancellationToken ct = default)
    {
        Directory.CreateDirectory(Dir);
        var file = Path.Combine(Dir, $"StreamPLAY-{r.Version.ToString(3)}.exe");
        if (!(File.Exists(file) && await IsValid(file, r)))
        {
            var part = file + ".part";
            using (var resp = await Http.GetAsync(r.Url, HttpCompletionOption.ResponseHeadersRead, ct))
            {
                resp.EnsureSuccessStatusCode();
                long total = resp.Content.Headers.ContentLength ?? r.Size;
                await using var src = await resp.Content.ReadAsStreamAsync(ct);
                await using var dst = File.Create(part);
                var buf = new byte[81920];
                long got = 0;
                int n;
                while ((n = await src.ReadAsync(buf, ct)) > 0)
                {
                    await dst.WriteAsync(buf.AsMemory(0, n), ct);
                    got += n;
                    if (total > 0) progress?.Report(Math.Min(1, got / (double)total));
                }
            }
            if (!await IsValid(part, r)) { File.Delete(part); throw new IOException("O download da atualização veio corrompido."); }
            File.Move(part, file, true);
        }
        progress?.Report(1);
        _readyFile = file;
        Ready = r;
    }

    static async Task<bool> IsValid(string file, Release r)
    {
        if (r.Size > 0 && new FileInfo(file).Length != r.Size) return false;
        if (r.Sha256 == null) return true;
        await using var fs = File.OpenRead(file);
        var hash = Convert.ToHexString(await SHA256.HashDataAsync(fs)).ToLowerInvariant();
        return hash == r.Sha256;
    }

    /// <summary>Dá para trocar o exe? (não dá se ele estiver numa pasta protegida, tipo Arquivos de Programas)</summary>
    public static bool CanInstall()
    {
        try
        {
            var probe = Path.Combine(Path.GetDirectoryName(ExePath)!, ".streamplay-teste-" + Environment.ProcessId);
            File.WriteAllText(probe, "");
            File.Delete(probe);
            return true;
        }
        catch { return false; }
    }

    /// <summary>Troca o exe pela versão baixada. Com restart, abre a versão nova (o chamador fecha o app).</summary>
    public static bool Install(bool restart)
    {
        if (_readyFile == null || !File.Exists(_readyFile)) return false;
        var exe = ExePath;
        var old = exe + ".old";
        try
        {
            if (File.Exists(old)) File.Delete(old);
            File.Move(exe, old);
            try { File.Copy(_readyFile, exe, true); }
            catch { File.Move(old, exe); throw; }
            try { File.Delete(_readyFile); } catch { }
            _readyFile = null;
            if (restart) Process.Start(new ProcessStartInfo(exe) { UseShellExecute = true, WorkingDirectory = Path.GetDirectoryName(exe)! });
            return true;
        }
        catch (Exception ex) { App.Log(ex); return false; }
    }

    /// <summary>Na abertura: apaga o exe antigo e downloads que ficaram pela metade.</summary>
    public static void Cleanup()
    {
        try { if (ExePath != null && File.Exists(ExePath + ".old")) File.Delete(ExePath + ".old"); } catch { }
        try
        {
            if (!Directory.Exists(Dir)) return;
            foreach (var f in Directory.GetFiles(Dir))
            {
                var name = Path.GetFileNameWithoutExtension(f.Replace(".part", ""));
                bool stale = f.EndsWith(".part") || (Version.TryParse(name.Replace("StreamPLAY-", ""), out var v) && Normalize(v) <= Current);
                if (stale) File.Delete(f);
            }
        }
        catch { }
    }

    static bool Bool(JsonElement e, string name) => e.TryGetProperty(name, out var p) && p.ValueKind == JsonValueKind.True;
    static Version Normalize(Version v) => new(v.Major, Math.Max(0, v.Minor), Math.Max(0, v.Build));
}
