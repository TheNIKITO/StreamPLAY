using System.IO.Pipes;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace StreamPLAY;

/// <summary>
/// Status do Discord (Rich Presence): mostra no perfil o que a pessoa está assistindo. Conversa direto com o Discord
/// aberto no PC (pipe local \\.\pipe\discord-ipc-N), sem servidor. Sem o Discord aberto, não faz nada; quando ele abre,
/// o status aparece sozinho. Igual ao do ScreenPLAY (discord.js), em C#.
/// </summary>
public static class Discord
{
    const string AppId = "1565163621690908882";   // aplicativo "StreamPLAY" no portal de desenvolvedores do Discord
    const string Icone = "https://raw.githubusercontent.com/TheNIKITO/StreamPLAY/main/Assets/icon.png";
    const string Baixar = "https://github.com/TheNIKITO/StreamPLAY/releases/latest";

    /// <summary>O que mostrar. Fim/Inicio em ms desde 1970 (barra de progresso); Imagem = capa (https).</summary>
    public record Atividade(string Detalhe, string Estado, string Imagem, string TextoImagem, long? Inicio, long? Fim);

    // STREAMPLAY_DISCORD_LOG=arquivo grava o que o Discord respondeu (só para testes)
    static readonly string LogTeste = Environment.GetEnvironmentVariable("STREAMPLAY_DISCORD_LOG");

    static readonly object Trava = new();
    static NamedPipeClientStream _pipe;
    static bool _pronto, _iniciado, _semExtras;
    static Atividade _quero;
    static string _enviado;
    static DateTime _ultimoEnvio;
    static Timer _envio;

    /// <summary>Muda o status (null limpa). Junta mudanças seguidas: o Discord aceita ~5 a cada 20 s.</summary>
    public static void Mostrar(Atividade a)
    {
        if (!Store.D.DiscordStatus) a = null;
        lock (Trava)
        {
            _quero = a;
            if (!_iniciado) { _iniciado = true; _ = Task.Run(Conectar); }
            AgendarEnvio();
        }
    }

    // ---------- conexão ----------

    static async Task Conectar()
    {
        while (true)
        {
            for (int i = 0; i < 10 && _pipe == null; i++)
            {
                var p = new NamedPipeClientStream(".", "discord-ipc-" + i, PipeDirection.InOut, PipeOptions.Asynchronous);
                try { await p.ConnectAsync(300); }
                catch { p.Dispose(); continue; }
                try
                {
                    await Escrever(p, 0, new JsonObject { ["v"] = 1, ["client_id"] = AppId });
                    lock (Trava) { _pipe = p; _pronto = false; _enviado = null; }
                    await Ler(p);   // até o Discord fechar
                }
                catch (Exception ex) when (ex is IOException or ObjectDisposedException or InvalidOperationException or JsonException) { }
                finally
                {
                    lock (Trava) { if (_pipe == p) { _pipe = null; _pronto = false; } }
                    p.Dispose();
                }
            }
            await Task.Delay(15000);   // Discord fechado: tenta de novo daqui a pouco
        }
    }

    static async Task Ler(NamedPipeClientStream p)
    {
        var cab = new byte[8];
        while (true)
        {
            await LerTudo(p, cab);
            int op = BitConverter.ToInt32(cab, 0), n = BitConverter.ToInt32(cab, 4);
            if (n < 0 || n > 1 << 20) throw new IOException("resposta inválida do Discord");
            var corpo = new byte[n];
            await LerTudo(p, corpo);
            if (op == 2) throw new IOException("o Discord fechou a conexão");   // CLOSE (ex.: app id inválido)
            if (op == 3) { await Escrever(p, 4, JsonNode.Parse(corpo)); continue; }   // PING → PONG
            var j = JsonNode.Parse(corpo);
            if (!string.IsNullOrEmpty(LogTeste)) try { File.AppendAllText(LogTeste, $"{DateTime.Now:HH:mm:ss} {j?.ToJsonString()}\n"); } catch { }
            var evt = (string)j?["evt"];
            if (evt == "READY") lock (Trava) { _pronto = true; _enviado = null; AgendarEnvio(); }
            else if (evt == "ERROR" && (string)j?["cmd"] == "SET_ACTIVITY" && !_semExtras)
            {
                // Discord antigo não conhece algum campo novo: manda de novo só com o básico
                App.Log("Discord recusou o status: " + j?["data"]?.ToJsonString());
                lock (Trava) { _semExtras = true; _enviado = null; AgendarEnvio(); }
            }
        }
    }

    static async Task LerTudo(Stream s, byte[] buf)
    {
        int lido = 0;
        while (lido < buf.Length)
        {
            int n = await s.ReadAsync(buf.AsMemory(lido));
            if (n == 0) throw new IOException("o Discord fechou a conexão");
            lido += n;
        }
    }

    static readonly SemaphoreSlim Escrevendo = new(1, 1);
    static async Task Escrever(Stream s, int op, JsonNode corpo)
    {
        var b = Encoding.UTF8.GetBytes(corpo.ToJsonString());
        var msg = new byte[8 + b.Length];
        BitConverter.GetBytes(op).CopyTo(msg, 0);
        BitConverter.GetBytes(b.Length).CopyTo(msg, 4);
        b.CopyTo(msg, 8);
        await Escrevendo.WaitAsync();
        try { await s.WriteAsync(msg); await s.FlushAsync(); }
        finally { Escrevendo.Release(); }
    }

    // ---------- envio do status ----------

    // chamado com a Trava
    static void AgendarEnvio()
    {
        if (_pipe == null || !_pronto) return;
        var espera = TimeSpan.FromSeconds(4) - (DateTime.Now - _ultimoEnvio);
        if (espera < TimeSpan.Zero) espera = TimeSpan.Zero;
        _envio ??= new Timer(_ => _ = Enviar());
        _envio.Change(espera, Timeout.InfiniteTimeSpan);
    }

    static async Task Enviar()
    {
        NamedPipeClientStream p;
        JsonObject args;
        lock (Trava)
        {
            p = _pipe;
            if (p == null || !_pronto) return;
            var atividade = Montar(_quero);
            var json = atividade?.ToJsonString() ?? "null";
            if (json == _enviado) return;
            _enviado = json;
            _ultimoEnvio = DateTime.Now;
            args = new JsonObject { ["pid"] = Environment.ProcessId, ["activity"] = atividade };
        }
        try { await Escrever(p, 1, new JsonObject { ["cmd"] = "SET_ACTIVITY", ["args"] = args, ["nonce"] = Guid.NewGuid().ToString() }); }
        catch (Exception ex) when (ex is IOException or ObjectDisposedException) { }
    }

    static JsonObject Montar(Atividade a)
    {
        if (a == null) return null;
        static string Corta(string s) => string.IsNullOrWhiteSpace(s) ? null : s.Length <= 128 ? s.PadRight(2) : s[..127] + "…";
        var assets = new JsonObject
        {
            ["large_image"] = a.Imagem is { Length: <= 300 } img && img.StartsWith("https://", StringComparison.OrdinalIgnoreCase) ? img : Icone,
            ["large_text"] = Corta(a.TextoImagem ?? a.Detalhe),
            ["small_image"] = Icone,
            ["small_text"] = "StreamPLAY",
        };
        var j = new JsonObject
        {
            ["type"] = 3,   // "Assistindo"
            ["details"] = Corta(a.Detalhe),
            ["assets"] = assets,
        };
        if (Corta(a.Estado) is { } est) j["state"] = est;
        if (a.Inicio != null)
        {
            var ts = new JsonObject { ["start"] = a.Inicio };
            if (a.Fim != null) ts["end"] = a.Fim;
            j["timestamps"] = ts;
        }
        if (!_semExtras)
        {
            j["status_display_type"] = 2;   // na lista de membros: "Assistindo Mistérios do Vale" em vez de "Assistindo StreamPLAY"
            j["buttons"] = new JsonArray(new JsonObject { ["label"] = "Baixar StreamPLAY", ["url"] = Baixar });
        }
        return j;
    }
}
