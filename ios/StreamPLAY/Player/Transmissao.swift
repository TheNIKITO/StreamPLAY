import Foundation
import MobileVLCKit

/// Chromecast: o próprio VLC acha os aparelhos na rede e, com o aparelho escolhido no player, manda o vídeo para a TV
/// (convertendo no celular se o formato não servir). Igual ao Transmissao.kt do Android. A busca começa quando a
/// pessoa abre o painel de transmitir e para quando o player fecha.
@MainActor
final class Transmissao: NSObject, ObservableObject, VLCRendererDiscovererDelegate {
    static let s = Transmissao()

    private var busca: VLCRendererDiscoverer?

    /// Aparelhos achados até agora (na ordem em que apareceram).
    @Published private(set) var aparelhos: [VLCRendererItem] = []

    /// Para onde o player está transmitindo (nil = no próprio celular). Vale até a pessoa parar ou o app fechar.
    @Published private(set) var atual: VLCRendererItem?

    static func nome(_ a: VLCRendererItem) -> String { a.name.isEmpty ? "Chromecast" : a.name }

    static func mesmo(_ a: VLCRendererItem, _ b: VLCRendererItem) -> Bool { a.name == b.name && a.type == b.type }

    func ehOAtual(_ a: VLCRendererItem) -> Bool { atual.map { Transmissao.mesmo($0, a) } ?? false }

    func definirAtual(_ a: VLCRendererItem?) { atual = a }

    func iniciar() {
        if busca != nil { return }
        let nome = VLCRendererDiscoverer.list()?.first?.name ?? "microdns_renderer"
        guard let b = VLCRendererDiscoverer(name: nome) else { return }
        b.delegate = self
        if !b.start() { return }
        busca = b
    }

    /// O player fechou: para de procurar (a escolha atual continua guardada para o próximo título).
    func parar() {
        guard let b = busca else { return }
        busca = nil
        b.stop()
        b.delegate = nil
        aparelhos = []
    }

    nonisolated func rendererDiscovererItemAdded(_ rendererDiscoverer: VLCRendererDiscoverer, item: VLCRendererItem) {
        Task { @MainActor in
            if !self.aparelhos.contains(where: { Transmissao.mesmo($0, item) }) { self.aparelhos.append(item) }
        }
    }

    nonisolated func rendererDiscovererItemDeleted(_ rendererDiscoverer: VLCRendererDiscoverer, item: VLCRendererItem) {
        Task { @MainActor in self.aparelhos.removeAll { Transmissao.mesmo($0, item) } }
    }
}
