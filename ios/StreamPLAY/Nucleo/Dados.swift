import Foundation
import Security

struct Progresso {
    let pos: Int64
    let dur: Int64
    let at: Int64
    let show: String?

    var fraction: Double { dur > 0 ? min(1, max(0, Double(pos) / Double(dur))) : 0 }
    var finished: Bool { dur > 0 && Double(pos) >= Double(dur) * 0.95 }
}

/// Quando um item entrou (on) ou saiu da Minha lista, em ms UTC (a sincronização usa para saber o mais novo).
struct FavMarca {
    let on: Bool
    let at: Int64
}

/// Pastas do app: dados (guardados) e cache (podem ser apagados pelo sistema).
enum Pastas {
    static let dados: URL = {
        let u = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("StreamPLAY", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }()
    static let cache: URL = {
        let u = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("StreamPLAY", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }()
}

/// Dados do app no aparelho (favoritos, progresso, lista escolhida, conta). Igual ao dados.json do PC e do Android.
/// Mexa sempre na linha principal.
@MainActor
final class Store: ObservableObject {
    static let s = Store()

    var source: String?
    var autoNext = true
    var caching = 1500
    var tab = "home"
    var favorites: [String] = []
    var progress: [String: Progresso] = [:]
    var recent: [String] = []
    /// Novidades do PLAYGER que já apareceram neste aparelho (cada uma aparece uma vez).
    var novidadesVistas: [String] = []
    var favLog: [String: FavMarca] = [:]
    /// "Limpar continuar assistindo" (ms UTC): progresso mais antigo que isto não volta da nuvem.
    var progressoLimpo: Int64 = 0

    // Conta do PLAYGER: guarda só o id do acesso (hash de login + senha), nunca a senha.
    var login: String?
    var acessoId: String?
    var contaOk: Int64 = 0
    /// Código aleatório deste aparelho no limite de aparelhos do PLAYGER (criado na primeira vez).
    var aparelho: String?
    /// De qual lista é a cópia salva no aparelho (para abrir sem internet).
    var listaSalva: String?

    /// Muda a cada gravação: as telas que mostram favoritos e progresso se atualizam.
    @Published var versao = 0

    private let arquivo = Pastas.dados.appendingPathComponent("dados.json")
    private let gravador = DispatchQueue(label: "streamplay.dados")

    private init() {
        if let d = try? Data(contentsOf: arquivo), let j = jsonObj(d) { ler(j) }
    }

    private func ler(_ j: [String: Any]) {
        func s(_ k: String) -> String? { (j[k] as? String).flatMap { $0.isEmpty ? nil : $0 } }
        func n(_ k: String) -> Int64 { (j[k] as? NSNumber)?.int64Value ?? 0 }
        source = s("source")
        autoNext = (j["autoNext"] as? Bool) ?? true
        caching = (j["caching"] as? NSNumber)?.intValue ?? 1500
        tab = s("tab") ?? "home"
        login = s("login")
        acessoId = s("acessoId")
        contaOk = n("contaOk")
        aparelho = s("aparelho")
        listaSalva = s("listaSalva")
        favorites = (j["favorites"] as? [String] ?? []).filter { !$0.isEmpty }
        recent = (j["recent"] as? [String] ?? []).filter { !$0.isEmpty }
        novidadesVistas = (j["novidadesVistas"] as? [String] ?? []).filter { !$0.isEmpty }
        progressoLimpo = n("progressoLimpo")
        for (k, v) in j["favLog"] as? [String: [String: Any]] ?? [:] {
            favLog[k] = FavMarca(on: v["on"] as? Bool ?? false, at: (v["at"] as? NSNumber)?.int64Value ?? 0)
        }
        for (k, o) in j["progress"] as? [String: [String: Any]] ?? [:] {
            progress[k] = Progresso(pos: (o["pos"] as? NSNumber)?.int64Value ?? 0, dur: (o["dur"] as? NSNumber)?.int64Value ?? 0,
                                    at: (o["at"] as? NSNumber)?.int64Value ?? 0, show: (o["show"] as? String).flatMap { $0.isEmpty ? nil : $0 })
        }
    }

    func save() {
        versao += 1
        let d = jsonData(json())
        let f = arquivo
        gravador.async { try? d.write(to: f, options: .atomic) }
    }

    private func json() -> [String: Any] {
        var fl: [String: Any] = [:]
        for (k, v) in favLog { fl[k] = ["on": v.on, "at": v.at] }
        var p: [String: Any] = [:]
        for (k, v) in progress {
            var o: [String: Any] = ["pos": v.pos, "dur": v.dur, "at": v.at]
            if let s = v.show { o["show"] = s }
            p[k] = o
        }
        return [
            "source": source ?? "", "autoNext": autoNext, "caching": caching, "tab": tab,
            "login": login ?? "", "acessoId": acessoId ?? "", "contaOk": contaOk, "aparelho": aparelho ?? "",
            "listaSalva": listaSalva ?? "", "favorites": favorites, "recent": recent, "novidadesVistas": novidadesVistas,
            "progressoLimpo": progressoLimpo, "favLog": fl, "progress": p,
        ]
    }

    func meuAparelho() -> String {
        if let a = aparelho, a.count == 32 { return a }
        var b = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, b.count, &b)
        let a = hexDe(b)
        aparelho = a
        save()
        return a
    }

    func isFav(_ key: String) -> Bool { favorites.contains(key) }

    @discardableResult
    func toggleFav(_ key: String) -> Bool {
        let tinha = favorites.contains(key)
        favorites.removeAll { $0 == key }
        let on = !tinha
        if on { favorites.insert(key, at: 0) }
        favLog[key] = FavMarca(on: on, at: agoraMs())
        // só guarda os 100 "tirados" mais recentes (para a remoção chegar nos outros aparelhos)
        for (k, _) in favLog.filter({ !$0.value.on }).sorted(by: { $0.value.at > $1.value.at }).dropFirst(100) { favLog[k] = nil }
        Sincronia.sujo = true
        save()
        return on
    }

    func getProgress(_ key: String?) -> Progresso? { key.flatMap { progress[$0] } }

    func watched(_ key: String?) -> Double {
        guard let p = getProgress(key), !p.finished else { return 0 }
        return p.fraction
    }

    func setProgress(_ key: String, _ pos: Int64, _ dur: Int64, _ show: String?, save salvar: Bool = true) {
        if dur <= 0 { return }
        progress[key] = Progresso(pos: pos, dur: dur, at: agoraMs(), show: show)
        Sincronia.sujo = true
        if progress.count > 600 {
            for (k, _) in progress.sorted(by: { $0.value.at < $1.value.at }).prefix(100) { progress[k] = nil }
        }
        if salvar { save() }
    }

    func addRecent(_ key: String) {
        recent.removeAll { $0 == key }
        recent.insert(key, at: 0)
        if recent.count > 30 { recent.removeLast(recent.count - 30) }
        save()
    }
}

/// Um código deste aparelho guardado nas Chaves do iOS: continua o mesmo se o app for apagado e instalado de novo
/// (o teste grátis usa para não recomeçar a cada reinstalação).
enum Chaves {
    static func codigoFixo() -> String {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "streamplay",
                                kSecAttrAccount as String: "aparelho", kSecReturnData as String: true]
        var r: AnyObject?
        if SecItemCopyMatching(q as CFDictionary, &r) == errSecSuccess, let d = r as? Data, let s = String(data: d, encoding: .utf8), !s.isEmpty {
            return s
        }
        let novo = UUID().uuidString.lowercased()
        let add: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "streamplay",
                                  kSecAttrAccount as String: "aparelho", kSecValueData as String: Data(novo.utf8),
                                  kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock]
        SecItemAdd(add as CFDictionary, nil)
        return novo
    }
}
