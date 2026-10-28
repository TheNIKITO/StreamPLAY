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

    /// <summary>
    /// Devolve a pasta do VLC embutido, extraindo na primeira vez (leva alguns segundos). Null se não deu.
    /// Duas janelas do app abertas juntas extraem uma de cada vez (trava entre processos): a segunda espera e usa a
    /// pasta que a primeira preparou, em vez de apagar e trocar uma pasta que já está em uso.
    /// </summary>
    public static string Preparar()
    {
        if (Pasta == null) return null;
        if (Pronto) { LimparVersoesVelhas(); return Pasta; }
        using var trava = new Mutex(false, @"Local\StreamPLAY-vlc-" + Versao);
        bool minha = false;
        try
        {
            try { minha = trava.WaitOne(TimeSpan.FromMinutes(3)); }
            catch (AbandonedMutexException) { minha = true; }   // a outra janela fechou no meio: a vez é nossa
            if (!minha) return Pronto ? Pasta : null;
            if (Pronto) return Pasta;   // a outra janela terminou enquanto esperávamos
            return Extrair();
        }
        finally
        {
            if (minha) trava.ReleaseMutex();
        }
    }

    // Só roda com a trava: nenhuma outra janela está extraindo ao mesmo tempo.
    static string Extrair()
    {
        var tmp = $"{Pasta}.tmp-{Environment.ProcessId}";
        var prioridade = Thread.CurrentThread.Priority;
        Thread.CurrentThread.Priority = ThreadPriority.BelowNormal;   // não deixa a tela travar enquanto extrai
        try
        {
            Directory.CreateDirectory(Raiz);
            // sobras de extrações interrompidas (app fechado no meio): ninguém as usa, porque sem .pronto não valem
            foreach (var d in Directory.GetDirectories(Raiz, Versao + "*"))
                if (!ExtraindoAgora(d) && !ApagarSeLivre(d) && string.Equals(d, Pasta, StringComparison.OrdinalIgnoreCase))
                    throw new IOException("a pasta do VLC está em uso e incompleta: " + d);
            if (!ExtrairPacote(tmp)) return null;
            // STREAMPLAY_TESTE_VLC_LENTO=segundos imita um PC fraco (antivírus conferindo cada arquivo extraído)
            if (int.TryParse(Environment.GetEnvironmentVariable("STREAMPLAY_TESTE_VLC_LENTO"), out var lento)) Thread.Sleep(lento * 1000);
            File.WriteAllText(Path.Combine(tmp, ".pronto"), Versao);
            Directory.Move(tmp, Pasta);
            LimparVersoesVelhas();
            return Pronto ? Pasta : null;
        }
        catch (Exception ex) { App.Log(ex); return null; }
        finally
        {
            Thread.CurrentThread.Priority = prioridade;
            ApagarSeLivre(tmp);
        }
    }

    /// <summary>
    /// Apaga a pasta só se nenhum app aberto estiver usando o VLC dela (senão ele perderia metade dos arquivos no
    /// meio do uso). Quem usa o VLC tem o libvlc.dll carregado, e aí o Windows não deixa abrir o arquivo com
    /// exclusividade. (Renomear a pasta não serve de teste: o Windows deixa, mesmo com o VLC carregado.)
    /// </summary>
    static bool ApagarSeLivre(string pasta)
    {
        if (!Directory.Exists(pasta)) return true;
        foreach (var dll in new[] { "libvlc.dll", "libvlccore.dll" })
        {
            var f = Path.Combine(pasta, dll);
            if (!File.Exists(f)) continue;
            try { using (File.Open(f, FileMode.Open, FileAccess.ReadWrite, FileShare.None)) { } }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { return false; }
        }
        var lixo = Path.Combine(Raiz, ".apagar-" + Guid.NewGuid().ToString("N")[..8]);
        try { Directory.Move(pasta, lixo); }   // tira do caminho na hora; o apagar pode demorar
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { return false; }
        try { Directory.Delete(lixo, true); } catch { }
        return true;
    }

    // Em método separado para o VlcPack.dll (~40 MB) só ser carregado quando realmente precisa extrair.
    [MethodImpl(MethodImplOptions.NoInlining)]
    static bool ExtrairPacote(string destino)
    {
        using var zip = VlcPacote.Abrir();
        if (zip == null) return false;
        using var arq = new ZipArchive(zip, ZipArchiveMode.Read);
        arq.ExtractToDirectory(destino, overwriteFiles: true);
        foreach (var f in Directory.EnumerateFiles(Path.Combine(destino, "plugins"), "*.dll", SearchOption.AllDirectories))
            File.SetLastWriteTimeUtc(f, DataDosModulos);
        return true;
    }

    // Versões antigas do motor (de antes de uma atualização do app) e sobras. Uma janela antiga ainda aberta pode
    // estar usando a dela: essa fica para a próxima vez que o app abrir.
    static void LimparVersoesVelhas()
    {
        try
        {
            var pastas = Directory.GetDirectories(Raiz);
            if (pastas.Length < 2) return;
            foreach (var d in pastas)
                if (!string.Equals(d, Pasta, StringComparison.OrdinalIgnoreCase) && !ExtraindoAgora(d)) ApagarSeLivre(d);
        }
        catch { }
    }

    // "{versão}.tmp-{processo}" de um app de outra versão que ainda está extraindo (a trava é por versão)
    static bool ExtraindoAgora(string pasta)
    {
        var i = pasta.LastIndexOf(".tmp-", StringComparison.Ordinal);
        if (i < 0 || !int.TryParse(pasta[(i + 5)..], out var pid)) return false;
        try { using var p = System.Diagnostics.Process.GetProcessById(pid); return !p.HasExited; }
        catch { return false; }
    }
}
