import Foundation

struct Programa {
    let inicio: Int64
    let fim: Int64
    let titulo: String
    let descricao: String?

    var horario: String { "\(Guia.hhmm(inicio))–\(Guia.hhmm(fim))" }
    func progresso(_ agora: Int64) -> Double { fim <= inicio ? 0 : min(1, max(0, Double(agora - inicio) / Double(fim - inicio))) }
}

/// Guia de programação (EPG) dos canais ao vivo — o mesmo do Android (Guia.kt) e do PC. A lista diz de onde vem o
/// guia (#EXTM3U url-tvg="...") e cada canal diz o seu código nele (tvg-id). O XMLTV é baixado direto da fonte, fica
/// salvo no aparelho e é renovado a cada 6 horas; dele só se guarda a programação dos canais da lista, das últimas
/// horas até as próximas 36 horas.
final class Guia: ObservableObject {
    static let s = Guia()
    private static let umaHora: Int64 = 60 * 60 * 1000
    private static let validade: TimeInterval = 6 * 60 * 60

    private var grade: [String: [Programa]] = [:]
    private var carga = 0

    /// Muda quando a programação é carregada (as telas redesenham).
    @Published var versao = 0

    var temAlgum: Bool { !grade.isEmpty }

    /// O programa que está passando agora e o próximo (qualquer um pode ser nil).
    func agoraESeguir(_ canal: Item?, _ agora: Int64) -> (Programa?, Programa?) {
        guard let id = canal?.tvgId?.lowercased(), let lista = grade[id] else { return (nil, nil) }
        for (i, p) in lista.enumerated() {
            if p.fim <= agora { continue }
            if p.inicio <= agora { return (p, i + 1 < lista.count ? lista[i + 1] : nil) }
            return (nil, p)   // buraco na grade: nada agora, mas tem o próximo
        }
        return (nil, nil)
    }

    /// Os próximos programas depois do que está passando (painel do canal na aba Canais).
    func proximos(_ canal: Item?, _ agora: Int64, _ quantos: Int) -> [Programa] {
        guard let id = canal?.tvgId?.lowercased(), let lista = grade[id] else { return [] }
        return Array(lista.lazy.filter { $0.inicio > agora }.prefix(quantos))
    }

    /// Carrega o guia para os canais desta lista (chamar na linha principal). Pode ser chamado de novo a qualquer hora.
    @MainActor
    func carregar(_ lib: Library) async {
        carga += 1
        let minha = carga
        let nova = await Task.detached(priority: .utility) { await Guia.montar(lib, agoraMs()) }.value
        guard let nova = nova, minha == carga else { return }   // sem guia desta vez, ou chegou uma lista mais nova
        grade = nova
        versao += 1
    }

    private static func montar(_ lib: Library, _ agora: Int64) async -> [String: [Programa]]? {
        var ids = Set<String>()
        for c in lib.allChannels { if let t = c.tvgId { ids.insert(t.lowercased()) } }
        var g: [String: [Programa]] = [:]
        if ids.isEmpty { return g }
        var algum = false
        for url in lib.guias {
            guard let arquivo = await obter(url), let d = try? Data(contentsOf: arquivo, options: .alwaysMapped) else { continue }
            algum = true
            Leitor(ids: ids, agora: agora).ler(d, &g)
        }
        if !algum && !lib.guias.isEmpty { return nil }
        for k in g.keys { g[k]!.sort { $0.inicio < $1.inicio } }
        return g
    }

    // Arquivo do guia salvo no aparelho: baixa de novo se passou da validade. Sem internet, usa o salvo.
    private static func obter(_ url: String) async -> URL? {
        let pasta = Pastas.cache.appendingPathComponent("guia", isDirectory: true)
        try? FileManager.default.createDirectory(at: pasta, withIntermediateDirectories: true)
        let arquivo = pasta.appendingPathComponent(hex(hash64(url)) + ".xml")
        let fm = FileManager.default
        if let m = (try? fm.attributesOfItem(atPath: arquivo.path))?[.modificationDate] as? Date, Date().timeIntervalSince(m) < validade {
            return arquivo
        }
        do {
            let (d, h) = try await Rede.pedir(url, tempo: 60)
            guard (200...299).contains(h.statusCode) else { throw ErroHttp(codigo: h.statusCode) }
            // guia publicado compactado (.xml.gz)
            let xml = d.count > 2 && d[d.startIndex] == 0x1F && d[d.startIndex + 1] == 0x8B ? try gunzip(d) : d
            try xml.write(to: arquivo, options: .atomic)
            return arquivo
        } catch {
            return fm.fileExists(atPath: arquivo.path) ? arquivo : nil
        }
    }

    /// .gz = cabeçalho + "deflate" + 8 bytes no fim; o iOS sabe abrir o "deflate" puro.
    static func gunzip(_ d: Data) throws -> Data {
        let b = [UInt8](d)
        guard b.count > 18, b[0] == 0x1F, b[1] == 0x8B, b[2] == 8 else { throw CocoaError(.fileReadCorruptFile) }
        let flags = b[3]
        var i = 10
        if flags & 4 != 0 { i += 2 + Int(b[i]) + Int(b[i + 1]) << 8 }   // FEXTRA
        if flags & 8 != 0 { while i < b.count && b[i] != 0 { i += 1 }; i += 1 }   // FNAME
        if flags & 16 != 0 { while i < b.count && b[i] != 0 { i += 1 }; i += 1 }   // FCOMMENT
        if flags & 2 != 0 { i += 2 }   // FHCRC
        guard i < b.count - 8 else { throw CocoaError(.fileReadCorruptFile) }
        let corpo = Data(b[i..<(b.count - 8)]) as NSData
        return try corpo.decompressed(using: .zlib) as Data
    }

    // Lê o XMLTV aos poucos, guardando só os canais pedidos e a janela de tempo útil.
    private final class Leitor: NSObject, XMLParserDelegate {
        let ids: Set<String>
        let de: Int64, ate: Int64
        var grade: [String: [Programa]] = [:]
        var canal: String?
        var inicio: Int64 = 0, fim: Int64 = 0
        var titulo: String?, desc: String?
        var texto: String?
        var campo = ""

        init(ids: Set<String>, agora: Int64) {
            self.ids = ids
            de = agora - 3 * Guia.umaHora
            ate = agora + 36 * Guia.umaHora
        }

        func ler(_ d: Data, _ g: inout [String: [Programa]]) {
            grade = g
            let p = XMLParser(data: d)
            p.shouldResolveExternalEntities = false
            p.delegate = self
            p.parse()
            g = grade
        }

        func parser(_ parser: XMLParser, didStartElement nome: String, namespaceURI: String?, qualifiedName: String?, attributes a: [String: String] = [:]) {
            if nome == "programme" {
                canal = nil
                guard let c = a["channel"]?.lowercased(), ids.contains(c), let i = Guia.hora(a["start"]), let f = Guia.hora(a["stop"]),
                      !(f < de || i > ate) else { return }
                canal = c; inicio = i; fim = f; titulo = nil; desc = nil
            } else if canal != nil && ((nome == "title" && titulo == nil) || (nome == "desc" && desc == nil)) {
                campo = nome
                texto = ""
            }
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            texto?.append(string)
        }

        func parser(_ parser: XMLParser, didEndElement nome: String, namespaceURI: String?, qualifiedName: String?) {
            if let t = texto, nome == campo {
                let v = t.trimmingCharacters(in: .whitespacesAndNewlines)
                if campo == "title" { titulo = v } else { desc = v }
                texto = nil
            } else if nome == "programme" {
                guard let c = canal else { return }
                canal = nil
                guard let tit = titulo, !tit.isEmpty else { return }
                grade[c, default: []].append(Programa(inicio: inicio, fim: fim, titulo: tit, descricao: (desc ?? "").isEmpty ? nil : desc))
            }
        }
    }

    private static let utc: Calendar = { var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: "UTC")!; return c }()

    // XMLTV: "20261026190000 +0000" (o fuso é opcional; sem ele vale UTC como no PC). Devolve em milissegundos.
    static func hora(_ s: String?) -> Int64? {
        guard let s = s else { return nil }
        let u = Array(s.utf8)
        guard u.count >= 14 else { return nil }
        func n(_ a: Int, _ b: Int) -> Int? {
            var v = 0
            for i in a..<b { let c = u[i]; guard c >= 48 && c <= 57 else { return nil }; v = v * 10 + Int(c - 48) }
            return v
        }
        guard let y = n(0, 4), let mo = n(4, 6), let d = n(6, 8), let h = n(8, 10), let mi = n(10, 12), let se = n(12, 14),
              let data = utc.date(from: DateComponents(year: y, month: mo, day: d, hour: h, minute: mi, second: se)) else { return nil }
        var t = Int64(data.timeIntervalSince1970) * 1000
        let fuso = String(decoding: u[14...], as: UTF8.self).trimmingCharacters(in: .whitespaces)
        let fu = Array(fuso.utf8)
        if fu.count == 5 && (fu[0] == 43 || fu[0] == 45), let hh = Int(fuso.dropFirst().prefix(2)), let mm = Int(fuso.suffix(2)) {
            let min = Int64(hh * 60 + mm)
            t -= (fu[0] == 45 ? -min : min) * 60_000
        }
        return t
    }

    private static let fmt: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f
    }()

    /// "19:30" no horário do aparelho.
    static func hhmm(_ t: Int64) -> String { fmt.string(from: Date(timeIntervalSince1970: Double(t) / 1000)) }
}
