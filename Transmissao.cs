using System.Windows;
using LibVLCSharp.Shared;

namespace StreamPLAY;

/// <summary>
/// Chromecast: o próprio VLC acha os aparelhos na rede (mDNS) e, com o aparelho escolhido no player, manda o vídeo
/// para a TV (se o formato não servir para o Chromecast, ele converte no PC). A busca só começa quando a pessoa abre
/// o painel de transmitir pela primeira vez: abrir a porta de rede faz o Firewall do Windows perguntar se libera o
/// StreamPLAY, e quem não tem Chromecast nunca precisa ver essa pergunta.
/// </summary>
public static class Transmissao
{
    static RendererDiscoverer _busca;
    static readonly List<RendererItem> _aparelhos = new();

    /// <summary>Aparelhos achados até agora (na ordem em que apareceram).</summary>
    public static IReadOnlyList<RendererItem> Aparelhos => _aparelhos;

    /// <summary>Para onde o player está transmitindo (null = no próprio PC). Vale até a pessoa parar ou fechar o app.</summary>
    public static RendererItem Atual { get; set; }

    /// <summary>A lista de aparelhos mudou (sempre chamado na thread da tela).</summary>
    public static event Action Mudou;

    public static bool Buscando => _busca != null;

    public static void Iniciar()
    {
        if (_busca != null || App.VLC == null) return;
        try
        {
            // no Windows o VLC tem um só tipo de busca de aparelhos (microdns_renderer, o do Chromecast)
            var nome = App.VLC.RendererList.Select(r => r.Name).FirstOrDefault() ?? "microdns_renderer";
            var busca = new RendererDiscoverer(App.VLC, nome);
            busca.ItemAdded += (_, e) => NaTela(() =>
            {
                if (_aparelhos.Any(a => Mesmo(a, e.RendererItem))) return;
                _aparelhos.Add(e.RendererItem);
                Mudou?.Invoke();
            });
            busca.ItemDeleted += (_, e) => NaTela(() =>
            {
                _aparelhos.RemoveAll(a => Mesmo(a, e.RendererItem));
                Mudou?.Invoke();
            });
            if (!busca.Start()) { App.Log("Chromecast: o VLC não conseguiu começar a procurar aparelhos (" + nome + ")."); busca.Dispose(); return; }
            _busca = busca;
        }
        catch (Exception ex) { App.Log(ex); }
    }

    static bool Mesmo(RendererItem a, RendererItem b) => a.Name == b.Name && a.Type == b.Type;

    public static bool EhOAtual(RendererItem a) => Atual != null && Mesmo(a, Atual);

    static void NaTela(Action a) => Application.Current?.Dispatcher.BeginInvoke(a);
}
