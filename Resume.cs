namespace StreamPLAY;

public class PlayRequest
{
    public Item Item { get; init; }
    public Episode Episode { get; init; }
    public List<Item> Channels { get; init; }
    public bool FromStart { get; set; }

    public bool IsLive => Item?.Kind == Kind.Channel;
    public string Url => Episode?.Url ?? Item?.Url;
    public List<string> Options => Episode?.Options ?? Item?.Options ?? new();
    public string Key => Episode?.Key ?? Item?.Key;
    public string ShowKey => Episode?.Show?.Key;
    public string Title => Episode != null ? Episode.Show.Name : Item?.Name;

    public static PlayRequest ForItem(Item i) => i == null ? null : i.Kind switch
    {
        Kind.Series => ForEpisode(Resume.EpisodeFor(i)),
        Kind.Channel => ForChannel(i, null),
        _ => new PlayRequest { Item = i },
    };

    public static PlayRequest ForEpisode(Episode e) => e == null ? null : new PlayRequest { Item = e.Show, Episode = e };

    public static PlayRequest ForChannel(Item c, List<Item> list) => new() { Item = c, Channels = list?.Count > 0 ? list : new List<Item> { c } };
}

/// <summary>Regras de "continuar assistindo" e próximo episódio.</summary>
public static class Resume
{
    public static Episode EpisodeFor(Item show)
    {
        if (show == null || show.Episodes.Count == 0) return null;
        Episode best = null;
        Progress bp = null;
        foreach (var e in show.Episodes)
        {
            var p = Store.GetProgress(e.Key);
            if (p != null && (bp == null || p.At > bp.At)) { best = e; bp = p; }
        }
        if (best == null) return show.Episodes[0];
        if (bp.Finished) return Next(best) ?? show.Episodes[0];
        return best;
    }

    public static Episode Next(Episode e)
    {
        if (e?.Show == null) return null;
        var list = e.Show.Episodes;
        int i = list.IndexOf(e);
        return i >= 0 && i + 1 < list.Count ? list[i + 1] : null;
    }

    /// <param name="kind">null = filmes e séries juntos (página Início)</param>
    public static List<Card> ContinueCards(Library lib, Kind? kind)
    {
        var cards = new List<Card>();
        var seen = new HashSet<string>();
        foreach (var (key, p) in Store.D.Progress.OrderByDescending(kv => kv.Value.At))
        {
            if (kind != Kind.Series && key.StartsWith("mv:"))
            {
                var it = lib.Get(key);
                if (it == null || p.Finished || p.Pos < 20000) continue;
                cards.Add(new Card(it) { Progress = p.Fraction, Sub = UI.Left(p.Dur - p.Pos), Direct = true });
            }
            else if (kind != Kind.Movie && key.StartsWith("ep:"))
            {
                if (!lib.Episodes.TryGetValue(key[3..], out var ep) || !seen.Add(ep.Show.Key)) continue;
                var target = ep;
                double frac = p.Fraction;
                if (p.Finished) { target = Next(ep); frac = Store.Watched(target?.Key); if (target == null) continue; }
                else if (p.Pos < 20000) continue;
                cards.Add(new Card(ep.Show) { Ep = target, Progress = Math.Max(frac, 0.006), Sub = $"T{target.Season} • E{target.Number}" });
            }
            if (cards.Count >= 20) break;
        }
        return cards;
    }
}
