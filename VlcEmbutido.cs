using System.IO.Compression;
using System.Reflection;
using System.Runtime.CompilerServices;

namespace StreamPLAY;

/// <summary>
/// O motor do VLC vem dentro do app (VlcPack.dll guarda um vlc.zip). Na primeira vez ele é extraído para
/// %LOCALAPPDATA%\StreamPLAY\vlc\{versão}; das outras vezes já está pronto. Assim ninguém precisa instalar o VLC.
/// </summary>
public static class VlcEmbutido
{
    public static string Versao { get; } = typeof(VlcEmbutido).Assembly.GetCustomAttributes<AssemblyMetadataAttribute>()
        .FirstOrDefault(a => a.Key == "VlcVersao")?.Value;

    // com STREAMPLAY_DATA (testes) fica junto dos dados de teste; no normal, em LocalAppData (não vai para o perfil móvel)
    static readonly string Raiz = Environment.GetEnvironmentVariable("STREAMPLAY_DATA") is { Length: > 0 }
        ? Path.Combine(Store.Dir, "vlc")
        : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "StreamPLAY", "vlc");

    // Data com que os módulos são marcados ao extrair: o índice VlcPack\plugins.dat foi gerado com ela
    // (o VLC só usa o índice se data e tamanho de cada módulo baterem).
    static readonly DateTime DataDosModulos = new(2024, 1, 1, 0, 0, 0, DateTimeKind.Utc);

    public static string Pasta => Versao == null ? null : Path.Combine(Raiz, Versao);
    static string Marca => Path.Combine(Pasta, ".pronto");
    public static bool Pronto => Pasta != null && File.Exists(Marca);

    /// <summary>Devolve a pasta do VLC embutido, extraindo na primeira vez (leva alguns segundos). Null se não deu.</summary>
    public static string Preparar()
    {
        if (Pasta == null) return null;
        if (Pronto) return Pasta;
        var tmp = $"{Pasta}.tmp-{Environment.ProcessId}";
        var prioridade = Thread.CurrentThread.Priority;
        Thread.CurrentThread.Priority = ThreadPriority.BelowNormal;   // não deixa a tela travar enquanto extrai
        try
        {
            if (Directory.Exists(tmp)) Directory.Delete(tmp, true);
            if (!Extrair(tmp)) return null;
            File.WriteAllText(Path.Combine(tmp, ".pronto"), Versao);
            try
            {
                if (Directory.Exists(Pasta)) Directory.Delete(Pasta, true);   // sobra de uma extração interrompida
                Directory.Move(tmp, Pasta);
            }
            catch (IOException) when (Pronto) { }   // outra janela do app terminou de extrair primeiro
            LimparVersoesVelhas();
            return Pronto ? Pasta : null;
        }
        catch (Exception ex) { App.Log(ex); return null; }
        finally
        {
            Thread.CurrentThread.Priority = prioridade;
            try { if (Directory.Exists(tmp)) Directory.Delete(tmp, true); } catch { }
        }
    }

    // Em método separado para o VlcPack.dll (~40 MB) só ser carregado quando realmente precisa extrair.
    [MethodImpl(MethodImplOptions.NoInlining)]
    static bool Extrair(string destino)
    {
        using var zip = VlcPacote.Abrir();
        if (zip == null) return false;
        using var arq = new ZipArchive(zip, ZipArchiveMode.Read);
        arq.ExtractToDirectory(destino, overwriteFiles: true);
        foreach (var f in Directory.EnumerateFiles(Path.Combine(destino, "plugins"), "*.dll", SearchOption.AllDirectories))
            File.SetLastWriteTimeUtc(f, DataDosModulos);
        return true;
    }

    static void LimparVersoesVelhas()
    {
        try
        {
            foreach (var d in Directory.GetDirectories(Raiz))
                if (!string.Equals(d, Pasta, StringComparison.OrdinalIgnoreCase) && !d.Contains(".tmp-"))
                    try { Directory.Delete(d, true); } catch { }
        }
        catch { }
    }
}
