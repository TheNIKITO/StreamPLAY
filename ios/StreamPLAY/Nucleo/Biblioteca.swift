import Foundation
import CryptoKit

enum Kind: Int, CaseIterable { case channel = 0, movie, series }

final class Category {
    let name: String
    let kind: Kind
    let isRelease: Bool
    let order: Int
    var items: [Item] = []

    init(_ name: String, _ kind: Kind, _ isRelease: Bool, _ order: Int) {
        self.name = name
        self.kind = kind
        self.isRelease = isRelease
        self.order = order
    }
}

/// Filme, canal ou série. O id são os 8 primeiros bytes do SHA-1 (o mesmo do PC e do Android), então a chave
/// ("mv:..."), o progresso e a Minha lista são os mesmos em todos os aparelhos da conta.
final class Item: Hashable, Identifiable {
    let id: Int64
    let kind: Kind
    let name: String
    var logo: String?
    let url: String?
    let options: [String]
    let order: Int
    let key: String

    var backdrop: String?
    var plot: String?
    var year: String?
    var genre: String?
    var rating: String?
    var isRelease = false
    /// Canal: código no guia de programação (tvg-id).
    var tvgId: String?
    /// Nome normalizado, calculado na primeira busca.
    var busca: String?

    private(set) var episodes: [Episode] = []
    private(set) var categories: [String] = []

    init(_ id: Int64, _ kind: Kind, _ name: String, _ logo: String?, _ url: String?, _ options: [String], _ order: Int) {
        self.id = id
        self.kind = kind
        self.name = name
        self.logo = logo
        self.url = url
        self.options = options
        self.order = order
        self.key = Item.prefixo(kind) + hex(id)
    }

    static func == (a: Item, b: Item) -> Bool { a === b }
    func hash(into h: inout Hasher) { h.combine(ObjectIdentifier(self)) }

    func addEpisode(_ e: Episode) { episodes.append(e) }

    func sortEpisodes() {
        episodes.sort { ($0.season, $0.number, $0.order) < ($1.season, $1.number, $1.order) }
    }

    /// Devolve false se o item já estava nessa categoria.
    func addCategory(_ name: String) -> Bool {
        if categories.contains(name) { return false }
        categories.append(name)
        return true
    }

    var hasBackdrop: Bool { !(backdrop ?? "").isEmpty }
    var heroImage: String? { hasBackdrop ? backdrop : logo }
    var kindLabel: String {
        switch kind { case .movie: return "F I L M E"; case .series: return "S É R I E"; case .channel: return "C A N A L" }
    }
    var category: String { categories.first ?? "" }
    var seasons: [Int] { Array(Set(episodes.map { $0.season })).sorted() }

    var countText: String {
        let s = seasons.count
        return s > 1 ? "\(s) temporadas" : episodes.count == 1 ? "1 episódio" : "\(episodes.count) episódios"
    }

    var meta: String {
        var parts: [String] = []
        if let y = year, !y.isEmpty { parts.append(y) }
        if kind == .series { parts.append(countText) }
        if let g = genre, !g.isEmpty { parts.append(g) } else if !category.isEmpty { parts.append(category) }
        return parts.joined(separator: "  •  ")
    }

    static func prefixo(_ k: Kind) -> String {
        switch k { case .channel: return "ch:"; case .movie: return "mv:"; case .series: return "sr:" }
    }
}

final class Episode: Hashable, Identifiable {
    let id: Int64
    let season: Int
    let number: Int
    private let titulo: String?
    let url: String
    let logo: String?
    let plot: String?
    let options: [String]
    // a série é dona dos episódios; "unowned" evita o ciclo (sem ele a lista antiga nunca sairia da memória)
    unowned let show: Item
    let order: Int
    let key: String

    init(_ id: Int64, _ season: Int, _ number: Int, _ titulo: String?, _ url: String, _ logo: String?, _ plot: String?,
         _ options: [String], _ show: Item, _ order: Int) {
        self.id = id
        self.season = season
        self.number = number
        self.titulo = titulo
        self.url = url
        self.logo = logo
        self.plot = plot
        self.options = options
        self.show = show
        self.order = order
        self.key = "ep:" + hex(id)
    }

    static func == (a: Episode, b: Episode) -> Bool { a === b }
    func hash(into h: inout Hasher) { h.combine(ObjectIdentifier(self)) }

    var title: String { titulo ?? "Episódio \(number)" }
    var label: String { "T\(season):E\(number)" }
    var image: String? { (logo ?? "").isEmpty ? show.logo : logo }
}

/// Cartão das fileiras e grades: um item + o estado de exibição.
struct Cartao: Identifiable {
    let item: Item
    var ep: Episode? = nil
    var progress: Double = 0
    var sub: String? = nil
    var isNew = false
    /// tocar já começa a tocar (Continuar assistindo)
    var direct = false

    var id: String { item.key + "|" + (ep?.key ?? "") }
    var hasProgress: Bool { progress > 0.005 }
}

func iniciais(_ nome: String?) -> String {
    (nome ?? "").split(separator: " ").prefix(2).map { String($0.prefix(1)).uppercased() }.joined()
}

final class Library {
    var channels: [Category] = []
    var movies: [Category] = []
    var series: [Category] = []
    var allChannels: [Item] = []
    var allMovies: [Item] = []
    var allSeries: [Item] = []
    var mapas: [[Int64: Item]] = [[:], [:], [:]]
    var episodios: [Int64: Episode] = [:]
    /// Links de guia de programação (XMLTV) do cabeçalho: #EXTM3U url-tvg="..."
    var guias: [String] = []

    func cats(_ k: Kind) -> [Category] {
        switch k { case .channel: return channels; case .movie: return movies; case .series: return series }
    }

    func setCats(_ k: Kind, _ v: [Category]) {
        switch k { case .channel: channels = v; case .movie: movies = v; case .series: series = v }
    }

    func all(_ k: Kind) -> [Item] {
        switch k { case .channel: return allChannels; case .movie: return allMovies; case .series: return allSeries }
    }

    func addAll(_ k: Kind, _ i: Item) {
        switch k { case .channel: allChannels.append(i); case .movie: allMovies.append(i); case .series: allSeries.append(i) }
    }

    func get(_ key: String?) -> Item? {
        guard let key = key, key.utf8.count == 19 else { return nil }
        let k: Kind
        switch key.prefix(3) {
        case "ch:": k = .channel
        case "mv:": k = .movie
        case "sr:": k = .series
        default: return nil
        }
        guard let id = parseHex(key.dropFirst(3)) else { return nil }
        return mapas[k.rawValue][id]
    }

    func episode(_ key: String?) -> Episode? {
        guard let key = key, key.hasPrefix("ep:"), let id = parseHex(key.dropFirst(3)) else { return nil }
        return episodios[id]
    }

    var summary: String {
        "\(allMovies.count) filme\(allMovies.count == 1 ? "" : "s")  •  " +
            "\(allSeries.count) série\(allSeries.count == 1 ? "" : "s")  •  " +
            "\(allChannels.count) cana\(allChannels.count == 1 ? "l" : "is")"
    }
}

func hex(_ v: Int64) -> String { String(format: "%016llx", UInt64(bitPattern: v)) }

func parseHex<S: StringProtocol>(_ s: S) -> Int64? {
    guard s.utf8.count == 16, let u = UInt64(s, radix: 16) else { return nil }
    return Int64(bitPattern: u)
}

/// Os 8 primeiros bytes do SHA-1 (o PC e o Android usam o mesmo).
func hash64(_ s: String) -> Int64 {
    var v: UInt64 = 0
    for (i, b) in Insecure.SHA1.hash(data: Data(s.utf8)).enumerated() where i < 8 { v = (v << 8) | UInt64(b) }
    return Int64(bitPattern: v)
}

extension NSRegularExpression {
    convenience init(_ p: String, ic: Bool = true) {
        try! self.init(pattern: p, options: ic ? [.caseInsensitive] : [])
    }

    /// Os grupos do primeiro encontro (nil = grupo que não participou).
    func grupos(_ s: String) -> [String?]? {
        let ns = s as NSString
        guard let m = firstMatch(in: s, range: NSRange(location: 0, length: ns.length)) else { return nil }
        return (0..<m.numberOfRanges).map { i in
            let r = m.range(at: i)
            return r.location == NSNotFound ? nil : ns.substring(with: r)
        }
    }

    func acha(_ s: String) -> Bool {
        firstMatch(in: s, range: NSRange(location: 0, length: (s as NSString).length)) != nil
    }
}

/// Leitura da lista M3U — as mesmas regras do app do PC (Library.cs) e do Android (Biblioteca.kt).
enum M3U {
    // "FILMES | Lançamentos", "SERIES - Ação", "CANAIS: Esportes", "CANAIS = Esportes", "FILMES" ...
    static let groupRx = NSRegularExpression(#"^\s*(filmes?|movies?|vod|s[eé]ries?|seriados?|canais|canal)(?![a-zà-ÿ])\s*[|:\-–—/>=]*\s*(.*)$"#)
    // "Nome S01E02", "Nome - S1 E2 - Título", "Nome T01E02", "Nome 1x02"
    static let epRx = NSRegularExpression(#"^(.*?)[\s._\-–|:]*(?:[ST](\d{1,2})\s*[._\-]?\s*E[Pp]?\s*(\d{1,4})|(\d{1,2})x(\d{1,4}))(?![0-9])(.*)$"#)
    static let epRx2 = NSRegularExpression(#"^(.*?)[\s._\-–|:]*Temporada\s*(\d{1,2})[\s._\-–|:]*Epis[oó]dio\s*(\d{1,4})(.*)$"#)
    static let guiaRx = NSRegularExpression(#"(?:url-tvg|x-tvg-url)\s*=\s*"([^"]+)""#)
    static let deRx = NSRegularExpression(#"^(de|da|do|das|dos)\s+"#)

    static let videoExt = [".mp4", ".mkv", ".avi", ".mov", ".wmv", ".m4v", ".webm", ".flv", ".mpg", ".mpeg", ".divx"]
    static let conectivos: Set<String> = ["De", "Da", "Do", "Das", "Dos", "E", "Em", "Na", "No", "Para"]

    static func isUrl(_ s: String?) -> Bool {
        guard let s = s?.lowercased() else { return false }
        return s.hasPrefix("http://") || s.hasPrefix("https://")
    }

    private static let lf = UInt8(ascii: "\n"), cr = UInt8(ascii: "\r"), esp = UInt8(ascii: " "), tab = UInt8(ascii: "\t")
    private static let aspas = UInt8(ascii: "\""), virgula = UInt8(ascii: ","), igual = UInt8(ascii: "="), doisPontos = UInt8(ascii: ":")

    /// Texto de um trecho de bytes. Listas antigas salvas em Latin-1 (Bloco de Notas) continuam com os acentos certos.
    private static func texto(_ p: UnsafeBufferPointer<UInt8>, _ a: Int, _ b: Int) -> String {
        if a >= b { return "" }
        let fatia = UnsafeBufferPointer(rebasing: p[a..<b])
        var alto = false
        for c in fatia where c >= 0x80 { alto = true; break }
        if !alto { return String(decoding: fatia, as: UTF8.self) }
        return String(bytes: fatia, encoding: .utf8) ?? String(bytes: fatia, encoding: .isoLatin1) ?? String(decoding: fatia, as: UTF8.self)
    }

    private static func comeca(_ p: UnsafeBufferPointer<UInt8>, _ a: Int, _ b: Int, _ pre: StaticString) -> Bool {
        let n = pre.utf8CodeUnitCount
        if b - a < n { return false }
        let q = pre.utf8Start
        for i in 0..<n {
            var c = p[a + i]
            if c >= 97 && c <= 122 { c -= 32 }
            if c != q[i] { return false }
        }
        return true
    }

    /// Lê a lista linha a linha. `progresso` recebe a fração do arquivo já lida (0 a 1).
    static func parse(_ dados: Data, baseDir: String?, progresso: ((Double) -> Void)? = nil) -> Library {
        dados.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Library in
            parse(raw.bindMemory(to: UInt8.self), baseDir: baseDir, progresso: progresso)
        }
    }

    private static func parse(_ p: UnsafeBufferPointer<UInt8>, baseDir: String?, progresso: ((Double) -> Void)?) -> Library {
        let lib = Library()
        var cats: [String: Category] = [:]        // tipo + nome normalizado
        var catRapido: [String: Category] = [:]   // tipo + nome como veio
        var grupos: [String: (Kind?, String?)] = [:]
        var bonitos: [String: String] = [:]
        var shows: [String: Item] = [:]
        var inf: [UInt8]? = nil
        var grp: String? = nil
        var opts: [String]? = nil
        var order = 0
        var catOrder = 0
        var n = 0
        let total = max(1, p.count)
        var i = 0
        // BOM do UTF-8
        if p.count >= 3 && p[0] == 0xEF && p[1] == 0xBB && p[2] == 0xBF { i = 3 }

        while i < p.count {
            var j = i
            while j < p.count && p[j] != lf { j += 1 }
            var a = i, b = j
            i = j + 1
            while a < b && (p[a] == esp || p[a] == tab || p[a] == cr) { a += 1 }
            while b > a && (p[b - 1] == esp || p[b - 1] == tab || p[b - 1] == cr) { b -= 1 }
            if a == b { continue }

            if p[a] == UInt8(ascii: "#") {
                if comeca(p, a, b, "#EXTINF") { inf = Array(p[a..<b]); grp = nil; opts = nil; continue }
                if comeca(p, a, b, "#EXTM3U") {
                    // guia de programação: url-tvg="link" (ou x-tvg-url), podendo ter vários separados por vírgula
                    let linha = texto(p, a, b)
                    let ns = linha as NSString
                    for m in guiaRx.matches(in: linha, range: NSRange(location: 0, length: ns.length)) {
                        for u in ns.substring(with: m.range(at: 1)).split(separator: ",") {
                            let u = u.trimmingCharacters(in: .whitespaces)
                            if isUrl(u) && !lib.guias.contains(u) { lib.guias.append(u) }
                        }
                    }
                    continue
                }
                if comeca(p, a, b, "#EXTGRP:") { grp = texto(p, a + 8, b).trimmingCharacters(in: .whitespaces); continue }
                if comeca(p, a, b, "#EXTVLCOPT:") {
                    if opts == nil { opts = [] }
                    opts!.append(texto(p, a + 11, b).trimmingCharacters(in: .whitespaces))
                    continue
                }
                continue
            }

            let url = resolve(texto(p, a, b), baseDir)
            var attrs: [String: String] = [:]
            let title = inf.map { parseInf($0, &attrs) } ?? ""
            inf = nil
            let name = firstNonEmpty(title, attrs["tvg-name"], nameFromUrl(url))
            // igual ao Android/PC: sem grupo, vira "Sem nome" (e o tipo sai pelo link)
            let group: String? = firstNonEmpty(attrs["group-title"], grp)
            let logo = attr(attrs, "tvg-logo", "logo", "capa", "poster").map { resolve($0, baseDir) }
            let backdrop = attr(attrs, "backdrop", "fundo", "tvg-backdrop", "banner", "background").map { resolve($0, baseDir) }

            let chaveGrupo = group ?? "\u{0}"
            let cl: (Kind?, String?)
            if let c = grupos[chaveGrupo] { cl = c } else { cl = classify(group); grupos[chaveGrupo] = cl }
            let kind: Kind
            if let k = cl.0 {
                kind = k
            } else {
                let path = (url.split(separator: "?", maxSplits: 1).first.map(String.init) ?? url).lowercased()
                let video = videoExt.contains { path.hasSuffix($0) }
                if path.contains("/series/") || (video && (epRx.acha(name) || epRx2.acha(name))) { kind = .series }
                else if path.contains("/movie/") || video { kind = .movie }
                else { kind = .channel }
            }
            let cru: String
            if let c = cl.1, !c.trimmingCharacters(in: .whitespaces).isEmpty { cru = c }
            else { cru = kind == .movie ? "Filmes" : kind == .series ? "Séries" : "Canais" }
            let cat: String
            if let bo = bonitos[cru] { cat = bo } else { cat = pretty(cru); bonitos[cru] = cat }

            let rapido = "\(kind.rawValue)\(cat)"
            let category: Category
            if let c = catRapido[rapido] {
                category = c
            } else {
                let k = "\(kind.rawValue)" + norm(cat)
                if let c = cats[k] {
                    category = c
                } else {
                    category = Category(cat, kind, isReleaseName(cat), catOrder)
                    catOrder += 1
                    cats[k] = category
                    lib.setCats(kind, lib.cats(kind) + [category])
                }
                catRapido[rapido] = category
            }

            let plot = attr(attrs, "tvg-plot", "plot", "descricao", "descrição", "description", "sinopse")
            let year = attr(attrs, "year", "ano", "tvg-year")
            let genre = attr(attrs, "genre", "genero", "gênero", "tvg-genre")
            let rating = attr(attrs, "rating", "nota", "tvg-rating")
            let itemOpts = opts ?? []

            if kind == .series {
                let sp = splitEpisode(name)
                let showKey = norm(sp.show)
                let show: Item
                if let s = shows[showKey] {
                    show = s
                } else {
                    show = Item(hash64(showKey), .series, sp.show, nil, nil, [], order)
                    order += 1
                    shows[showKey] = show
                    lib.allSeries.append(show)
                    lib.mapas[Kind.series.rawValue][show.id] = show
                }
                fill(show, plot, year, genre, rating, backdrop)
                if (show.logo ?? "").isEmpty { show.logo = logo }
                var number = sp.number
                if number <= 0 { number = show.episodes.filter { $0.season == sp.season }.count + 1 }
                // ep-titulo / ep-descricao / ep-imagem: do próprio episódio (TMDB); sem eles, o título vem do nome
                let tituloEp = attr(attrs, "ep-titulo") ?? ((sp.title ?? "").trimmingCharacters(in: .whitespaces).isEmpty ? nil : sp.title)
                let ep = Episode(hash64("\(url)|\(name)"), sp.season, number, tituloEp, url, attr(attrs, "ep-imagem") ?? logo,
                                 attr(attrs, "ep-descricao") ?? plot, itemOpts, show, order)
                order += 1
                show.addEpisode(ep)
                lib.episodios[ep.id] = ep
                if category.isRelease { show.isRelease = true }
                addTo(category, show)
            } else {
                // mesmo link + mesmo nome = mesmo item (aparece em várias categorias); nomes diferentes ficam separados
                let id = hash64(url + "|" + norm(name))
                let item: Item
                if let it = lib.mapas[kind.rawValue][id] {
                    item = it
                } else {
                    item = Item(id, kind, name, logo, url, itemOpts, order)
                    order += 1
                    lib.mapas[kind.rawValue][id] = item
                    lib.addAll(kind, item)
                }
                fill(item, plot, year, genre, rating, backdrop)
                if (item.logo ?? "").isEmpty { item.logo = logo }
                if kind == .channel && item.tvgId == nil { item.tvgId = attr(attrs, "tvg-id") }
                if category.isRelease { item.isRelease = true }
                addTo(category, item)
            }
            opts = nil
            n += 1
            if let pr = progresso, n % 5000 == 0 { pr(Double(i) / Double(total)) }
        }

        for s in lib.allSeries { s.sortEpisodes() }
        for k in Kind.allCases {
            lib.setCats(k, lib.cats(k).sorted { ($0.isRelease ? 0 : 1, $0.order) < ($1.isRelease ? 0 : 1, $1.order) })
        }
        return lib
    }

    private static func addTo(_ c: Category, _ i: Item) {
        if i.addCategory(c.name) { c.items.append(i) }
    }

    private static func fill(_ t: Item, _ plot: String?, _ year: String?, _ genre: String?, _ rating: String?, _ backdrop: String?) {
        if t.plot == nil { t.plot = plot }
        if t.year == nil { t.year = year }
        if t.genre == nil { t.genre = genre }
        if t.rating == nil { t.rating = rating }
        if t.backdrop == nil { t.backdrop = backdrop }
    }

    static func classify(_ group: String?) -> (Kind?, String?) {
        guard let group = group, !group.trimmingCharacters(in: .whitespaces).isEmpty else { return (nil, nil) }
        guard let m = groupRx.grupos(group) else { return (nil, group.trimmingCharacters(in: .whitespaces)) }
        let w = norm(m[1])
        let k: Kind = (w.hasPrefix("FILME") || w.hasPrefix("MOVIE") || w == "VOD") ? .movie
            : (w.hasPrefix("SERIE") || w.hasPrefix("SERIADO")) ? .series : .channel
        var cat = (m[2] ?? "").trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "|-:/="))
            .trimmingCharacters(in: .whitespaces)
        let ns = cat as NSString
        cat = deRx.stringByReplacingMatches(in: cat, range: NSRange(location: 0, length: ns.length), withTemplate: "")
        if let f = cat.first { cat = String(f).uppercased(with: ptBR) + cat.dropFirst() }
        return (k, cat)
    }

    struct Partes { let show: String; let season: Int; let number: Int; let title: String? }

    static func splitEpisode(_ name: String) -> Partes {
        let n: String, s: String, e: String, r: String
        if let g = epRx.grupos(name) {
            n = g[1] ?? ""
            s = g[2] ?? g[4] ?? "1"
            e = g[3] ?? g[5] ?? "0"
            r = g[6] ?? ""
        } else if let g = epRx2.grupos(name) {
            n = g[1] ?? ""
            s = g[2] ?? "1"
            e = g[3] ?? "0"
            r = g[4] ?? ""
        } else {
            return Partes(show: name.trimmingCharacters(in: .whitespaces), season: 1, number: 0, title: nil)
        }
        if n.trimmingCharacters(in: .whitespaces).isEmpty {
            return Partes(show: name.trimmingCharacters(in: .whitespaces), season: 1, number: 0, title: nil)
        }
        let bordas = CharacterSet(charactersIn: "-–|:._")
        let show = trimFim(n.trimmingCharacters(in: .whitespaces), bordas).trimmingCharacters(in: .whitespaces)
        let rest = trimInicio(r.trimmingCharacters(in: .whitespaces), bordas).trimmingCharacters(in: .whitespaces)
        return Partes(show: show, season: Int(s) ?? 1, number: Int(e) ?? 0, title: rest)
    }

    private static func trimFim(_ s: String, _ c: CharacterSet) -> String {
        var t = Substring(s)
        while let u = t.unicodeScalars.last, c.contains(u) { t = t.dropLast() }
        return String(t)
    }

    private static func trimInicio(_ s: String, _ c: CharacterSet) -> String {
        var t = Substring(s)
        while let u = t.unicodeScalars.first, c.contains(u) { t = t.dropFirst() }
        return String(t)
    }

    private static func isW(_ c: UInt8) -> Bool {
        (c >= 48 && c <= 57) || (c >= 65 && c <= 90) || (c >= 97 && c <= 122) || c == 95 || c == 45 || c >= 0x80
    }

    private static func isEspaco(_ c: UInt8) -> Bool { c == esp || c == tab || c == cr || c == lf }

    /// Separa os atributos (tvg-name="..." group-title="...") do título depois da vírgula.
    static func parseInf(_ linha: [UInt8], _ attrs: inout [String: String]) -> String {
        linha.withUnsafeBufferPointer { s -> String in
            var start = 0
            while start < s.count && s[start] != doisPontos { start += 1 }
            start += 1
            var comma = -1
            var q = false
            var i = start
            while i < s.count {
                let c = s[i]
                if c == aspas { q.toggle() } else if c == virgula && !q { comma = i; break }
                i += 1
            }
            let end = comma >= 0 ? comma : s.count
            // equivale à regex ([\w-]+)\s*=\s*"([^"]*)" do PC, sem o custo da regex em listas enormes
            i = min(start, end)
            while i < end {
                if !isW(s[i]) { i += 1; continue }
                let ini = i
                while i < end && isW(s[i]) { i += 1 }
                let fimNome = i
                var j = i
                while j < end && isEspaco(s[j]) { j += 1 }
                if j >= end || s[j] != igual { continue }
                j += 1
                while j < end && isEspaco(s[j]) { j += 1 }
                if j >= end || s[j] != aspas { i = j; continue }
                var fimValor = j + 1
                while fimValor < s.count && s[fimValor] != aspas { fimValor += 1 }
                if fimValor >= s.count || fimValor > end { break }
                attrs[texto(s, ini, fimNome).lowercased()] = texto(s, j + 1, fimValor).trimmingCharacters(in: .whitespaces)
                i = fimValor + 1
            }
            return comma >= 0 ? texto(s, comma + 1, s.count).trimmingCharacters(in: .whitespaces) : ""
        }
    }

    private static func attr(_ attrs: [String: String], _ names: String...) -> String? {
        for n in names {
            if let v = attrs[n], !v.trimmingCharacters(in: .whitespaces).isEmpty { return v }
        }
        return nil
    }

    private static func firstNonEmpty(_ v: String?...) -> String {
        for s in v { if let s = s, !s.trimmingCharacters(in: .whitespaces).isEmpty { return s.trimmingCharacters(in: .whitespaces) } }
        return "Sem nome"
    }

    private static func nameFromUrl(_ url: String) -> String? {
        let p = isUrl(url) ? (URL(string: url)?.path ?? "") : url
        var n = p
        if let i = p.lastIndex(where: { $0 == "/" || $0 == "\\" }) { n = String(p[p.index(after: i)...]) }
        if let dot = n.lastIndex(of: "."), dot > n.startIndex { n = String(n[..<dot]) }
        n = n.replacingOccurrences(of: ".", with: " ").replacingOccurrences(of: "_", with: " ")
        return n.trimmingCharacters(in: .whitespaces).isEmpty ? nil : n
    }

    static func resolve(_ s0: String, _ base: String?) -> String {
        let s = s0.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "\""))
        if isUrl(s) || s.contains("://") || s.lowercased().hasPrefix("data:") { return s }
        if s.hasPrefix("/") { return s }
        guard let base = base, isUrl(base), let b = URL(string: base) else { return s }
        return URL(string: s, relativeTo: b)?.absoluteString ?? s
    }

    private static func isReleaseName(_ cat: String) -> Bool {
        let n = norm(cat)
        return n.contains("LANCAMENTO") || n.contains("NOVIDADE") || n.contains("ESTREIA") || n.contains("RELEASE") || n == "NOVOS"
    }

    /// "LANÇAMENTOS" -> "Lançamentos"; nomes já com maiúsculas e minúsculas ficam como estão.
    static func pretty(_ s0: String) -> String {
        let s = s0.trimmingCharacters(in: .whitespaces)
        if s.contains(where: { $0.isLowercase }) || s.count <= 3 { return s }
        let low = s.lowercased(with: ptBR)
        var t = ""
        var dentro = false
        for c in low {
            t += (!dentro && c.isLetter) ? String(c).uppercased(with: ptBR) : String(c)
            dentro = c.isLetter || c.isNumber || c == "'"
        }
        // conectivos em minúsculas: "Ação e Aventura"
        var out = ""
        var palavra = ""
        for c in t {
            if c.isLetter || c.isNumber { palavra.append(c); continue }
            out += conectivos.contains(palavra) ? palavra.lowercased(with: ptBR) : palavra
            palavra = ""
            out.append(c)
        }
        out += conectivos.contains(palavra) ? palavra.lowercased(with: ptBR) : palavra
        return out
    }

    /// Maiúsculas, sem acentos e com espaços simples (para comparar nomes).
    static func norm(_ s: String?) -> String {
        guard let s = s, !s.isEmpty else { return "" }
        let ascii = s.utf8.allSatisfy { $0 < 0x80 }
        let d = ascii ? s : s.folding(options: [.diacriticInsensitive], locale: nil)
        var out = ""
        out.reserveCapacity(d.utf8.count)
        var espaco = false
        for c in d.unicodeScalars {
            if c.properties.isWhitespace { espaco = true; continue }
            if espaco && !out.isEmpty { out.append(" ") }
            espaco = false
            out.unicodeScalars.append(c)
        }
        return out.uppercased()
    }
}
