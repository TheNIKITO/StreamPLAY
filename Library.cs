using System.ComponentModel;
using System.Globalization;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace StreamPLAY;

public enum Kind { Channel, Movie, Series }

public class Category
{
    public string Name { get; set; }
    public Kind Kind { get; set; }
    public bool IsRelease { get; set; }
    public int Order { get; set; }
    public List<Item> Items { get; } = new();
}

public class Item
{
    public string Id { get; set; }
    public Kind Kind { get; set; }
    public string Name { get; set; }
    public string Logo { get; set; }
    public string Backdrop { get; set; }
    public string Plot { get; set; }
    public string Year { get; set; }
    public string Genre { get; set; }
    public string Rating { get; set; }
    public string Url { get; set; }
    public List<string> Options { get; set; } = new();
    public List<string> Categories { get; } = new();
    public List<Episode> Episodes { get; } = new();
    public bool IsRelease { get; set; }
    public int Order { get; set; }

    public string Key => (Kind == Kind.Channel ? "ch:" : Kind == Kind.Movie ? "mv:" : "sr:") + Id;
    public bool HasBackdrop => !string.IsNullOrEmpty(Backdrop);
    public string HeroImage => HasBackdrop ? Backdrop : Logo;
    public string KindLabel => Kind == Kind.Movie ? "F I L M E" : Kind == Kind.Series ? "S É R I E" : "C A N A L";
    public string Category => Categories.FirstOrDefault() ?? "";

    public List<int> Seasons => Episodes.Select(e => e.Season).Distinct().OrderBy(s => s).ToList();

    public string Meta
    {
        get
        {
            var parts = new List<string>();
            if (!string.IsNullOrEmpty(Year)) parts.Add(Year);
            if (Kind == Kind.Series)
            {
                int s = Seasons.Count;
                parts.Add(s > 1 ? $"{s} temporadas" : Episodes.Count == 1 ? "1 episódio" : $"{Episodes.Count} episódios");
            }
            if (!string.IsNullOrEmpty(Genre)) parts.Add(Genre);
            else if (!string.IsNullOrEmpty(Category)) parts.Add(Category);
            return string.Join("  •  ", parts);
        }
    }
}

public class Episode
{
    public string Id { get; set; }
    public int Season { get; set; }
    public int Number { get; set; }
    public string Title { get; set; }
    public string Url { get; set; }
    public string Logo { get; set; }
    public string Plot { get; set; }
    public List<string> Options { get; set; } = new();
    public Item Show { get; set; }
    public int Order { get; set; }

    public string Key => "ep:" + Id;
    public string Label => $"T{Season}:E{Number}";
    public string Image => string.IsNullOrEmpty(Logo) ? Show?.Logo : Logo;
}

/// <summary>Cartão exibido nas fileiras/grades (embrulha um item + estado de exibição).</summary>
public class Card : INotifyPropertyChanged
{
    public Card(Item item) { Item = item; _fav = Store.IsFav(item.Key); }
    public Item Item { get; }
    public Episode Ep { get; init; }
    public string Name => Item.Name;
    public string Logo => Item.Logo;
    public double Progress { get; init; }
    public bool HasProgress => Progress > 0.005;
    public string Sub { get; init; }
    public bool IsNew { get; init; }
    public bool Direct { get; init; }   // clicar já começa a tocar (Continuar assistindo)
    public string Initials
    {
        get
        {
            var w = (Item.Name ?? "").Split(' ', StringSplitOptions.RemoveEmptyEntries);
            return string.Concat(w.Take(2).Select(x => char.ToUpperInvariant(x[0])));
        }
    }

    bool _fav;
    public bool IsFav { get => _fav; set { _fav = value; PropertyChanged?.Invoke(this, new(nameof(IsFav))); } }
    public event PropertyChangedEventHandler PropertyChanged;
}

public class Library
{
    public List<Category> Channels { get; } = new();
    public List<Category> Movies { get; } = new();
    public List<Category> Series { get; } = new();
    public List<Item> AllChannels { get; } = new();
    public List<Item> AllMovies { get; } = new();
    public List<Item> AllSeries { get; } = new();
    public Dictionary<string, Item> Items { get; } = new();
    public Dictionary<string, Episode> Episodes { get; } = new();

    public List<Category> Cats(Kind k) => k == Kind.Channel ? Channels : k == Kind.Movie ? Movies : Series;
    public List<Item> All(Kind k) => k == Kind.Channel ? AllChannels : k == Kind.Movie ? AllMovies : AllSeries;

    public Item Get(string key) => key != null && Items.TryGetValue(key, out var i) ? i : null;

    public string Summary =>
        $"{AllMovies.Count} filme{(AllMovies.Count == 1 ? "" : "s")}  •  {AllSeries.Count} série{(AllSeries.Count == 1 ? "" : "s")}  •  {AllChannels.Count} cana{(AllChannels.Count == 1 ? "l" : "is")}";
}

public static class M3U
{
    static readonly Regex AttrRx = new(@"([\w-]+)\s*=\s*""([^""]*)""", RegexOptions.Compiled);

    // "FILMES | Lançamentos", "SERIES - Ação", "CANAIS: Esportes", "FILMES" ...
    static readonly Regex GroupRx = new(
        @"^\s*(?<k>filmes?|movies?|vod|s[eé]ries?|seriados?|canais|canal)(?![a-zà-ÿ])\s*[|:\-–—/>]*\s*(?<c>.*)$",
        RegexOptions.Compiled | RegexOptions.IgnoreCase);

    // "Nome S01E02", "Nome - S1 E2 - Título", "Nome T01E02", "Nome 1x02"
    static readonly Regex EpRx = new(
        @"^(?<n>.*?)[\s._\-–|:]*(?:[ST](?<s>\d{1,2})\s*[._\-]?\s*E[Pp]?\s*(?<e>\d{1,4})|(?<s>\d{1,2})x(?<e>\d{1,4}))(?![0-9])(?<r>.*)$",
        RegexOptions.Compiled | RegexOptions.IgnoreCase);
    static readonly Regex EpRx2 = new(
        @"^(?<n>.*?)[\s._\-–|:]*Temporada\s*(?<s>\d{1,2})[\s._\-–|:]*Epis[oó]dio\s*(?<e>\d{1,4})(?<r>.*)$",
        RegexOptions.Compiled | RegexOptions.IgnoreCase);

    static readonly string[] VideoExt = { ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".m4v", ".webm", ".flv", ".mpg", ".mpeg", ".divx" };

    static readonly HttpClient Http = CreateHttp();
    static HttpClient CreateHttp()
    {
        var h = new HttpClient(new SocketsHttpHandler { AutomaticDecompression = System.Net.DecompressionMethods.All }) { Timeout = TimeSpan.FromSeconds(60) };
        h.DefaultRequestHeaders.UserAgent.ParseAdd("StreamPLAY/1.0 (VLC)");
        return h;
    }

    public static bool IsUrl(string s) => s.StartsWith("http://", StringComparison.OrdinalIgnoreCase) || s.StartsWith("https://", StringComparison.OrdinalIgnoreCase);

    public static async Task<Library> LoadAsync(string source)
    {
        string text, baseDir;
        if (IsUrl(source))
        {
            var bytes = await Download(source, conditional: false);
            text = Decode(bytes);
            baseDir = source;
        }
        else
        {
            var bytes = await ReadSharedAsync(source);
            text = Decode(bytes);
            baseDir = Path.GetDirectoryName(Path.GetFullPath(source));
        }
        return await Task.Run(() => Parse(text, baseDir));
    }

    // Lista online: guarda ETag/Last-Modified e o hash do conteúdo para só baixar e reorganizar quando mudar.
    static string _etag, _lastModified, _lastHash, _lastUrl;

    /// <summary>Baixa a lista online só se ela mudou desde a última vez; senão devolve null.</summary>
    public static async Task<Library> LoadIfChangedAsync(string url)
    {
        var bytes = await Download(url, conditional: true);
        if (bytes == null) return null;
        return await Task.Run(() => Parse(Decode(bytes), url));
    }

    static async Task<byte[]> Download(string url, bool conditional)
    {
        if (url != _lastUrl) { _etag = _lastModified = _lastHash = null; _lastUrl = url; }
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        if (conditional && _etag != null) req.Headers.TryAddWithoutValidation("If-None-Match", _etag);
        if (conditional && _lastModified != null) req.Headers.TryAddWithoutValidation("If-Modified-Since", _lastModified);
        using var resp = await Http.SendAsync(req);
        if (resp.StatusCode == System.Net.HttpStatusCode.NotModified) return null;
        resp.EnsureSuccessStatusCode();
        var bytes = await resp.Content.ReadAsByteArrayAsync();
        _etag = resp.Headers.ETag?.ToString();
        _lastModified = resp.Content.Headers.LastModified?.ToString("R");
        var hash = Convert.ToHexString(SHA1.HashData(bytes));
        if (conditional && hash == _lastHash) return null;
        _lastHash = hash;
        return bytes;
    }

    // O Bloco de Notas pode estar gravando o arquivo: tenta algumas vezes com compartilhamento.
    static async Task<byte[]> ReadSharedAsync(string path)
    {
        for (int i = 0; ; i++)
        {
            try
            {
                using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
                using var ms = new MemoryStream();
                await fs.CopyToAsync(ms);
                return ms.ToArray();
            }
            catch (IOException) when (i < 6) { await Task.Delay(150); }
        }
    }

    static string Decode(byte[] bytes)
    {
        var utf8 = new UTF8Encoding(false, true);
        try { return utf8.GetString(bytes).TrimStart('﻿'); }
        catch (DecoderFallbackException) { return Encoding.Latin1.GetString(bytes); }
    }

    public static Library Parse(string text, string baseDir)
    {
        var lib = new Library();
        var cats = new Dictionary<(Kind, string), Category>();
        var shows = new Dictionary<string, Item>();
        string inf = null, grp = null;
        var opts = new List<string>();
        int order = 0, catOrder = 0;

        foreach (var raw in text.Split('\n'))
        {
            var line = raw.Trim();
            if (line.Length == 0) continue;
            if (line.StartsWith("#EXTINF", StringComparison.OrdinalIgnoreCase)) { inf = line; grp = null; opts = new List<string>(); continue; }
            if (line.StartsWith("#EXTGRP:", StringComparison.OrdinalIgnoreCase)) { grp = line[8..].Trim(); continue; }
            if (line.StartsWith("#EXTVLCOPT:", StringComparison.OrdinalIgnoreCase)) { opts.Add(line[11..].Trim()); continue; }
            if (line.StartsWith("#")) continue;

            var url = Resolve(line, baseDir);
            var (attrs, title) = ParseInf(inf);
            inf = null;
            string name = FirstNonEmpty(title, A(attrs, "tvg-name"), NameFromUrl(url));
            string group = FirstNonEmpty(A(attrs, "group-title"), grp);
            string logo = A(attrs, "tvg-logo", "logo", "capa", "poster");
            if (!string.IsNullOrEmpty(logo)) logo = Resolve(logo, baseDir);
            string backdrop = A(attrs, "backdrop", "fundo", "tvg-backdrop", "banner", "background");
            if (!string.IsNullOrEmpty(backdrop)) backdrop = Resolve(backdrop, baseDir);

            var (kind, cat) = Classify(group);
            if (kind == null)
            {
                var path = url.Split('?')[0].ToLowerInvariant();
                bool video = VideoExt.Any(path.EndsWith);
                if (path.Contains("/series/") || (video && (EpRx.IsMatch(name) || EpRx2.IsMatch(name)))) kind = Kind.Series;
                else if (path.Contains("/movie/") || video) kind = Kind.Movie;
                else kind = Kind.Channel;
            }
            var k = kind.Value;
            if (string.IsNullOrWhiteSpace(cat)) cat = k == Kind.Movie ? "Filmes" : k == Kind.Series ? "Séries" : "Canais";
            cat = Pretty(cat);

            if (!cats.TryGetValue((k, Norm(cat)), out var category))
            {
                category = new Category { Name = cat, Kind = k, IsRelease = IsReleaseName(cat), Order = catOrder++ };
                cats[(k, Norm(cat))] = category;
                lib.Cats(k).Add(category);
            }

            var info = new Item
            {
                Plot = A(attrs, "tvg-plot", "plot", "descricao", "descrição", "description", "sinopse"),
                Year = A(attrs, "year", "ano", "tvg-year"),
                Genre = A(attrs, "genre", "genero", "gênero", "tvg-genre"),
                Rating = A(attrs, "rating", "nota", "tvg-rating"),
                Backdrop = backdrop,
            };

            if (k == Kind.Series)
            {
                var (showName, season, number, epTitle) = SplitEpisode(name);
                var showKey = Norm(showName);
                if (!shows.TryGetValue(showKey, out var show))
                {
                    show = new Item { Id = Hash(showKey), Kind = Kind.Series, Name = showName, Order = order++ };
                    shows[showKey] = show;
                    lib.AllSeries.Add(show);
                    lib.Items[show.Key] = show;
                }
                Fill(show, info);
                if (string.IsNullOrEmpty(show.Logo)) show.Logo = logo;
                if (number <= 0) number = show.Episodes.Count(e => e.Season == season) + 1;
                var ep = new Episode
                {
                    Id = Hash(url + "|" + name), Season = season, Number = number, Url = url, Logo = logo, Plot = info.Plot,
                    Title = string.IsNullOrWhiteSpace(epTitle) ? $"Episódio {number}" : epTitle, Show = show,
                    Options = opts, Order = order++,
                };
                show.Episodes.Add(ep);
                lib.Episodes[ep.Id] = ep;
                if (category.IsRelease) show.IsRelease = true;
                AddTo(category, show);
            }
            else
            {
                // mesmo link + mesmo nome = mesmo item (aparece em várias categorias); nomes diferentes ficam separados
                var id = Hash(url + "|" + Norm(name));
                var key = (k == Kind.Movie ? "mv:" : "ch:") + id;
                if (!lib.Items.TryGetValue(key, out var item))
                {
                    item = new Item { Id = id, Kind = k, Name = name, Logo = logo, Url = url, Options = opts, Order = order++ };
                    lib.Items[key] = item;
                    lib.All(k).Add(item);
                }
                Fill(item, info);
                if (string.IsNullOrEmpty(item.Logo)) item.Logo = logo;
                if (category.IsRelease) item.IsRelease = true;
                AddTo(category, item);
            }
            opts = new List<string>();
        }

        foreach (var s in lib.AllSeries)
            s.Episodes.Sort((a, b) => a.Season != b.Season ? a.Season.CompareTo(b.Season) : a.Number != b.Number ? a.Number.CompareTo(b.Number) : a.Order.CompareTo(b.Order));

        foreach (var list in new[] { lib.Movies, lib.Series, lib.Channels })
        {
            var sorted = list.OrderByDescending(c => c.IsRelease).ThenBy(c => c.Order).ToList();
            list.Clear();
            list.AddRange(sorted);
        }
        return lib;
    }

    static void AddTo(Category c, Item i)
    {
        if (!c.Items.Contains(i)) c.Items.Add(i);
        if (!i.Categories.Contains(c.Name)) i.Categories.Add(c.Name);
    }

    static void Fill(Item target, Item info)
    {
        target.Plot ??= Empty(info.Plot);
        target.Year ??= Empty(info.Year);
        target.Genre ??= Empty(info.Genre);
        target.Rating ??= Empty(info.Rating);
        target.Backdrop ??= Empty(info.Backdrop);
    }
    static string Empty(string s) => string.IsNullOrWhiteSpace(s) ? null : s;

    public static (Kind? kind, string cat) Classify(string group)
    {
        if (string.IsNullOrWhiteSpace(group)) return (null, null);
        var m = GroupRx.Match(group);
        if (!m.Success) return (null, group.Trim());
        var w = Norm(m.Groups["k"].Value);
        Kind k = w.StartsWith("FILME") || w.StartsWith("MOVIE") || w == "VOD" ? Kind.Movie
               : w.StartsWith("SERIE") || w.StartsWith("SERIADO") ? Kind.Series
               : Kind.Channel;
        var cat = m.Groups["c"].Value.Trim().Trim('|', '-', ':', '/').Trim();
        cat = Regex.Replace(cat, @"^(de|da|do|das|dos)\s+", "", RegexOptions.IgnoreCase);
        if (cat.Length > 0) cat = char.ToUpper(cat[0]) + cat[1..];
        return (k, cat);
    }

    static (string show, int season, int number, string title) SplitEpisode(string name)
    {
        var m = EpRx.Match(name);
        if (!m.Success) m = EpRx2.Match(name);
        if (!m.Success || string.IsNullOrWhiteSpace(m.Groups["n"].Value)) return (name.Trim(), 1, 0, null);
        var show = m.Groups["n"].Value.Trim().TrimEnd('-', '–', '|', ':', '.', '_').Trim();
        var rest = m.Groups["r"].Value.Trim().TrimStart('-', '–', '|', ':', '.', '_').Trim();
        return (show, int.Parse(m.Groups["s"].Value), int.Parse(m.Groups["e"].Value), rest);
    }

    static (Dictionary<string, string>, string) ParseInf(string line)
    {
        var attrs = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        if (line == null) return (attrs, "");
        int start = line.IndexOf(':') + 1, comma = -1;
        bool q = false;
        for (int i = start; i < line.Length; i++)
        {
            char c = line[i];
            if (c == '"') q = !q;
            else if (c == ',' && !q) { comma = i; break; }
        }
        var head = comma >= 0 ? line[start..comma] : line[start..];
        var title = comma >= 0 ? line[(comma + 1)..].Trim() : "";
        foreach (Match m in AttrRx.Matches(head)) attrs[m.Groups[1].Value] = m.Groups[2].Value.Trim();
        return (attrs, title);
    }

    static string A(Dictionary<string, string> a, params string[] names)
    {
        foreach (var n in names) if (a.TryGetValue(n, out var v) && !string.IsNullOrWhiteSpace(v)) return v;
        return null;
    }

    static string FirstNonEmpty(params string[] v) => v.FirstOrDefault(s => !string.IsNullOrWhiteSpace(s))?.Trim() ?? "Sem nome";

    static string NameFromUrl(string url)
    {
        try
        {
            var p = IsUrl(url) ? Uri.UnescapeDataString(new Uri(url).AbsolutePath) : url;
            var n = Path.GetFileNameWithoutExtension(p);
            return string.IsNullOrWhiteSpace(n) ? null : n.Replace('.', ' ').Replace('_', ' ');
        }
        catch { return null; }
    }

    static string Resolve(string s, string baseDir)
    {
        s = s.Trim().Trim('"');
        if (IsUrl(s) || s.Contains("://") || s.StartsWith("data:", StringComparison.OrdinalIgnoreCase)) return s;
        if (Path.IsPathRooted(s)) return s;
        if (baseDir == null) return s;
        try
        {
            if (IsUrl(baseDir)) return new Uri(new Uri(baseDir), s).ToString();
            return Path.GetFullPath(Path.Combine(baseDir, s));
        }
        catch { return s; }
    }

    static bool IsReleaseName(string cat)
    {
        var n = Norm(cat);
        return n.Contains("LANCAMENTO") || n.Contains("NOVIDADE") || n.Contains("ESTREIA") || n.Contains("RELEASE") || n == "NOVOS";
    }

    // "LANÇAMENTOS" -> "Lançamentos"; nomes já com maiúsculas e minúsculas ficam como estão.
    static string Pretty(string s)
    {
        s = s.Trim();
        bool hasLower = s.Any(char.IsLower);
        if (hasLower || s.Length <= 3) return s;
        var t = CultureInfo.GetCultureInfo("pt-BR").TextInfo.ToTitleCase(s.ToLowerInvariant());
        return Regex.Replace(t, @"\b(De|Da|Do|Das|Dos|E|Em|Na|No|Para)\b", m => m.Value.ToLowerInvariant());
    }

    public static string Norm(string s)
    {
        if (string.IsNullOrEmpty(s)) return "";
        var d = s.Normalize(NormalizationForm.FormD);
        var sb = new StringBuilder(d.Length);
        foreach (var c in d)
            if (CharUnicodeInfo.GetUnicodeCategory(c) != UnicodeCategory.NonSpacingMark) sb.Append(char.ToUpperInvariant(c));
        return Regex.Replace(sb.ToString(), @"\s+", " ").Trim();
    }

    public static string Hash(string s)
    {
        var b = SHA1.HashData(Encoding.UTF8.GetBytes(s));
        return Convert.ToHexString(b, 0, 8).ToLowerInvariant();
    }
}
