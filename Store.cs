using System.Text.Json;

namespace StreamPLAY;

public class Progress
{
    public long Pos { get; set; }
    public long Dur { get; set; }
    public DateTime At { get; set; }
    public string Show { get; set; }   // chave da série (para episódios)
    public double Fraction => Dur > 0 ? Math.Clamp(Pos / (double)Dur, 0, 1) : 0;
    public bool Finished => Dur > 0 && Pos >= Dur * 0.95;
}

public class AppData
{
    public string Source { get; set; }
    public int Volume { get; set; } = 80;
    public bool Fullscreen { get; set; } = true;
    public bool AutoNext { get; set; } = true;
    public int Caching { get; set; } = 1500;
    public string Tab { get; set; } = "movies";
    public List<string> Favorites { get; set; } = new();
    public Dictionary<string, Progress> Progress { get; set; } = new();
    public List<string> Recent { get; set; } = new();
    // Conta do PLAYGER: guarda só o id do acesso (hash de login + senha), nunca a senha.
    public string Login { get; set; }
    public string AcessoId { get; set; }
    public DateTime ContaOk { get; set; }
    // Código aleatório deste PC no limite de aparelhos (criado na primeira vez).
    public string Aparelho { get; set; }
}

public static class Store
{
    public static readonly string Dir = Environment.GetEnvironmentVariable("STREAMPLAY_DATA") is { Length: > 0 } custom
        ? custom
        : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "StreamPLAY");
    static readonly string FilePath = Path.Combine(Dir, "dados.json");
    static readonly JsonSerializerOptions Json = new() { WriteIndented = true };
    public static AppData D { get; private set; } = new();

    public static void Load()
    {
        try
        {
            if (File.Exists(FilePath)) D = JsonSerializer.Deserialize<AppData>(File.ReadAllText(FilePath)) ?? new AppData();
        }
        catch { D = new AppData(); }
        D.Favorites ??= new();
        D.Progress ??= new();
        D.Recent ??= new();
    }

    public static void Save()
    {
        try
        {
            Directory.CreateDirectory(Dir);
            var tmp = FilePath + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(D, Json));
            File.Move(tmp, FilePath, true);
        }
        catch { }
    }

    /// <summary>Código deste aparelho no limite de aparelhos do PLAYGER (32 letras hexadecimais, criado uma vez).</summary>
    public static string MeuAparelho()
    {
        if (D.Aparelho is not { Length: 32 })
        {
            D.Aparelho = Convert.ToHexStringLower(System.Security.Cryptography.RandomNumberGenerator.GetBytes(16));
            Save();
        }
        return D.Aparelho;
    }

    public static bool IsFav(string key) => D.Favorites.Contains(key);

    public static bool ToggleFav(string key)
    {
        bool on = !D.Favorites.Remove(key);
        if (on) D.Favorites.Insert(0, key);
        Save();
        return on;
    }

    public static Progress GetProgress(string key) => key != null && D.Progress.TryGetValue(key, out var p) ? p : null;

    public static double Watched(string key)
    {
        var p = GetProgress(key);
        return p == null || p.Finished ? 0 : p.Fraction;
    }

    public static void SetProgress(string key, long pos, long dur, string show, bool save = true)
    {
        if (dur <= 0) return;
        D.Progress[key] = new Progress { Pos = pos, Dur = dur, At = DateTime.Now, Show = show };
        if (D.Progress.Count > 600)
            foreach (var old in D.Progress.OrderBy(kv => kv.Value.At).Take(100).Select(kv => kv.Key).ToList()) D.Progress.Remove(old);
        if (save) Save();
    }

    public static void AddRecent(string key)
    {
        D.Recent.Remove(key);
        D.Recent.Insert(0, key);
        if (D.Recent.Count > 30) D.Recent.RemoveRange(30, D.Recent.Count - 30);
        Save();
    }
}
