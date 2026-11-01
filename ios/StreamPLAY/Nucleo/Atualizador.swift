import Foundation

/// Procura versão nova na última release do GitHub (arquivo StreamPLAY.ipa), como o PC e o Android. No iPhone o app
/// não se instala sozinho: avisa e abre a página para baixar o .ipa e instalar de novo pelo AltStore/Sideloadly.
enum Atualizador {
    private static let api = "https://api.github.com/repos/TheNIKITO/StreamPLAY/releases/latest"
    static let pagina = "https://github.com/TheNIKITO/StreamPLAY/releases/latest"

    struct Versao { let versao: String; let url: String }

    static var atual: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0" }

    /// Como a versão aparece para o cliente: o app ainda está em desenvolvimento ("BETA 1.3.5").
    static func rotulo(_ v: String) -> String { "BETA \(v)" }

    static func procurar() async throws -> Versao? {
        let (d, h) = try await Rede.pedir(api, cabecalhos: ["Accept": "application/vnd.github+json", "User-Agent": "StreamPLAY-iOS"])
        if h.statusCode == 404 { return nil }
        guard (200...299).contains(h.statusCode) else { throw ErroHttp(codigo: h.statusCode) }
        guard let j = jsonObj(d) else { return nil }
        var tag = j["tag_name"] as? String ?? ""
        while tag.first == "v" || tag.first == "V" { tag.removeFirst() }
        guard maisNova(tag, atual) else { return nil }
        // a release só conta para o iPhone quando já tem o .ipa (ele é montado na nuvem alguns minutos depois)
        let assets = j["assets"] as? [[String: Any]] ?? []
        guard let a = assets.first(where: { ($0["name"] as? String)?.lowercased() == "streamplay.ipa" }) ??
                assets.first(where: { ($0["name"] as? String)?.lowercased().hasSuffix(".ipa") == true }),
              let url = a["browser_download_url"] as? String else { return nil }
        return Versao(versao: tag, url: url)
    }

    static func maisNova(_ a: String, _ b: String) -> Bool {
        let x = a.split(separator: ".").map { Int($0) ?? 0 }
        let y = b.split(separator: ".").map { Int($0) ?? 0 }
        for i in 0..<max(x.count, y.count) {
            let d = (i < x.count ? x[i] : 0) - (i < y.count ? y[i] : 0)
            if d != 0 { return d > 0 }
        }
        return false
    }
}
