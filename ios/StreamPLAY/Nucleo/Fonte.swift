import Foundation
import CryptoKit

/// Baixa um link para um arquivo, aos poucos, contando o andamento e o SHA-1 do conteúdo. Com ETag/Last-Modified,
/// devolve nil quando o servidor responde 304 (não mudou).
final class Baixador: NSObject, URLSessionDataDelegate {
    struct Resultado { let hash: String; let etag: String?; let lastModified: String? }

    private var arquivo: FileHandle?
    private let destino: URL
    private var sha = Insecure.SHA1()
    private var total: Int64
    private var lidos: Int64 = 0
    private let andamento: (Double) -> Void
    private var resposta: HTTPURLResponse?
    private var cont: CheckedContinuation<Resultado?, Error>?
    private var erroGravacao: Error?

    private init(destino: URL, total: Int64, andamento: @escaping (Double) -> Void) {
        self.destino = destino
        self.total = total
        self.andamento = andamento
    }

    static func baixar(_ url: String, para destino: URL, etag: String?, lastModified: String?, total: Int64,
                       andamento: @escaping (Double) -> Void) async throws -> Resultado? {
        guard let u = URL(string: url) else { throw URLError(.badURL) }
        var r = URLRequest(url: u, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        if let e = etag { r.setValue(e, forHTTPHeaderField: "If-None-Match") }
        if let l = lastModified { r.setValue(l, forHTTPHeaderField: "If-Modified-Since") }
        let b = Baixador(destino: destino, total: total, andamento: andamento)
        let cfg = Rede.sessao.copy() as! URLSessionConfiguration
        cfg.timeoutIntervalForRequest = 30
        let s = URLSession(configuration: cfg, delegate: b, delegateQueue: nil)
        defer { s.finishTasksAndInvalidate() }
        return try await withCheckedThrowingContinuation { c in
            b.cont = c
            s.dataTask(with: r).resume()
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let h = response as? HTTPURLResponse else { completionHandler(.cancel); return }
        resposta = h
        if h.statusCode == 304 || !(200...299).contains(h.statusCode) { completionHandler(.cancel); return }
        // com compressão o servidor não diz o tamanho final: fica a estimativa (tamanho da última cópia)
        if h.expectedContentLength > 0, h.value(forHTTPHeaderField: "Content-Encoding") == nil { total = h.expectedContentLength }
        FileManager.default.createFile(atPath: destino.path, contents: nil)
        arquivo = try? FileHandle(forWritingTo: destino)
        completionHandler(arquivo == nil ? .cancel : .allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        sha.update(data: data)
        do { try arquivo?.write(contentsOf: data) } catch { erroGravacao = error; dataTask.cancel(); return }
        lidos += Int64(data.count)
        // sem o tamanho total, o andamento segue uma curva que se aproxima de 100% sem chegar
        andamento(total > 0 ? min(0.99, Double(lidos) / Double(total)) : 1 - 1 / (1 + Double(lidos) / 8e6))
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        try? arquivo?.close()
        arquivo = nil
        guard let c = cont else { return }
        cont = nil
        if let h = resposta {
            if h.statusCode == 304 { c.resume(returning: nil); return }
            if !(200...299).contains(h.statusCode) { c.resume(throwing: ErroHttp(codigo: h.statusCode)); return }
        }
        if let e = erroGravacao ?? error { c.resume(throwing: e); return }
        c.resume(returning: Resultado(hash: hexDe(sha.finalize()), etag: resposta?.value(forHTTPHeaderField: "ETag"),
                                      lastModified: resposta?.value(forHTTPHeaderField: "Last-Modified")))
    }
}

/// De onde vem a lista: um link (http/https) ou um arquivo escolhido no aparelho (copiado para dentro do app).
/// Cada arquivo é baixado e lido; a última cópia fica salva para abrir sem internet. Igual ao Fonte.kt do Android.
///
/// Canais à parte (como no PC): um canais.m3u ao lado da lista online (mesmo endereço) entra junto, sem precisar
/// de outro link. Cada arquivo é conferido separado, então quando só os canais mudam a lista grande não é baixada de novo.
enum Fonte {
    static let arquivoCanais = "canais.m3u"
    static let prefixoArquivo = "arquivo:"
    private static let salva = "lista.m3u"
    private static let salvaCanais = "canais.m3u"

    final class Versao { var etag: String?; var lastModified: String?; var hash: String?
        init(_ e: String? = nil, _ l: String? = nil, _ h: String? = nil) { etag = e; lastModified = l; hash = h }
    }

    private static let trava = NSLock()
    private static var versoes: [String: Versao] = [:]
    // última leitura de cada arquivo: quando só um muda, o outro não precisa ser baixado/lido de novo
    private static var fonte: String?
    private static var principal: Library?
    private static var canais: Library?

    static func isUrl(_ s: String?) -> Bool { M3U.isUrl(s) }

    /// Arquivo escolhido no aparelho: o app guarda uma cópia (o iOS não deixa ler o original depois).
    static var arquivoEscolhido: URL { Pastas.dados.appendingPathComponent("arquivo-escolhido.m3u") }

    /// Onde procurar o canais.m3u para esta lista (ou nil se não se aplica).
    static func companheira(_ source: String?) -> String? {
        guard let source = source, isUrl(source), let u = URL(string: source) else { return nil }
        let nome = u.lastPathComponent
        let l = nome.lowercased()
        // só para listas que são um arquivo .m3u publicado (não para links de painel com ?usuario=...)
        guard l.hasSuffix(".m3u") || l.hasSuffix(".m3u8"), l != arquivoCanais else { return nil }
        return URL(string: arquivoCanais, relativeTo: u)?.absoluteString
    }

    /// Andamento (0 a 1) repartido em etapas; só avisa a tela quando a % sobe.
    final class Andamento {
        private var pc = -1
        private let tela: (Double) -> Void
        init(_ tela: @escaping (Double) -> Void) { self.tela = tela }
        func marcar(_ v: Double) {
            let p = Int(min(1, max(0, v)) * 100)
            if p <= pc { return }
            pc = p
            let f = Double(p) / 100
            DispatchQueue.main.async { self.tela(f) }
        }
        func trecho(_ de: Double, _ ate: Double) -> (Double) -> Void { { [self] in marcar(de + (ate - de) * min(1, max(0, $0))) } }
    }

    /// Lê a lista e o canais.m3u ao lado dela. `progresso` recebe o andamento de 0 a 1 (na linha principal).
    static func carregar(_ fonte: String, progresso: @escaping (Double) -> Void) async throws -> Library {
        let an = Andamento(progresso)
        let a = try await lerOuSalva(fonte, salva, an.trecho(0, 0.55), an.trecho(0.55, 0.88))
        var b: Library? = nil
        if let comp = companheira(fonte) {
            b = try? await lerOuSalva(comp, salvaCanais, { _ in }, an.trecho(0.88, 0.97))   // sem canais.m3u: só a lista
        }
        if b == nil { try? FileManager.default.removeItem(at: Pastas.dados.appendingPathComponent(salvaCanais)) }
        trava.lock(); self.fonte = fonte; principal = a; canais = b; trava.unlock()
        an.marcar(0.97)
        let r = juntar(a, b)
        an.marcar(1)
        return r
    }

    /// Confere a lista e o canais.m3u; baixa só o que mudou. Devolve nil se nada mudou.
    static func carregarSeMudou(_ fonte: String) async throws -> Library? {
        trava.lock(); let mesma = fonte == self.fonte && principal != nil; trava.unlock()
        if !mesma { return try await carregar(fonte) { _ in } }
        var mudou = false
        if let l = try await ler(fonte, condicional: true, salva) { trava.lock(); principal = l; trava.unlock(); mudou = true }
        if let comp = companheira(fonte) {
            do {
                if let l = try await ler(comp, condicional: true, salvaCanais) { trava.lock(); canais = l; trava.unlock(); mudou = true }
            } catch let e as ErroHttp where e.codigo == 404 {
                // o canais.m3u foi apagado: os canais saem do app
                trava.lock()
                if canais != nil { canais = nil; mudou = true }
                versoes[comp] = nil
                trava.unlock()
                try? FileManager.default.removeItem(at: Pastas.dados.appendingPathComponent(salvaCanais))
            } catch {
                // sem internet: fica como está
            }
        }
        trava.lock(); let a = principal, b = canais; trava.unlock()
        return mudou ? juntar(a!, b) : nil
    }

    /// A última cópia baixada desta lista (e dos canais), para abrir sem internet (ou nil).
    static func carregarSalva(_ fonte: String) -> Library? {
        let f = Pastas.dados.appendingPathComponent(salva)
        guard let d = try? Data(contentsOf: f, options: .alwaysMapped) else { return nil }
        let a = M3U.parse(d, baseDir: isUrl(fonte) ? fonte : nil)
        var b: Library? = nil
        if let comp = companheira(fonte), let dc = try? Data(contentsOf: Pastas.dados.appendingPathComponent(salvaCanais), options: .alwaysMapped) {
            b = M3U.parse(dc, baseDir: comp)
        }
        return juntar(a, b)
    }

    /// Junta a lista principal com a de canais (b pode ser nil). Não mexe nas duas — cria categorias novas.
    static func juntar(_ a: Library, _ b: Library?) -> Library {
        guard let b = b, !(b.allChannels.isEmpty && b.allMovies.isEmpty && b.allSeries.isEmpty && b.guias.isEmpty) else { return a }
        let r = Library()
        for lib in [a, b] {
            for k in Kind.allCases {
                for i in lib.all(k) where r.mapas[k.rawValue][i.id] == nil {
                    r.mapas[k.rawValue][i.id] = i
                    r.addAll(k, i)
                }
            }
            for (id, e) in lib.episodios where r.episodios[id] == nil { r.episodios[id] = e }
        }
        for g in b.guias + a.guias where !r.guias.contains(g) { r.guias.append(g) }
        for k in Kind.allCases {
            var porNome: [String: Category] = [:]
            var jaTem: [String: Set<Item>] = [:]
            var lista: [Category] = []
            for c in a.cats(k) + b.cats(k) {
                let itens = c.items.map { r.mapas[k.rawValue][$0.id] ?? $0 }
                let n = M3U.norm(c.name)
                guard let dest = porNome[n] else {
                    let nova = Category(c.name, k, c.isRelease, porNome.count)
                    nova.items = itens
                    porNome[n] = nova
                    lista.append(nova)
                    continue
                }
                // mesma categoria nas duas listas: soma os itens sem repetir
                var tem = jaTem[n] ?? Set(dest.items)
                for i in itens where tem.insert(i).inserted { dest.items.append(i) }
                jaTem[n] = tem
            }
            r.setCats(k, lista.sorted { ($0.isRelease ? 0 : 1, $0.order) < ($1.isRelease ? 0 : 1, $1.order) })
        }
        return r
    }

    /// Ao abrir o app: com a cópia salva deste link, só pergunta ao servidor se mudou (ETag/Last-Modified).
    private static func lerOuSalva(_ origem: String, _ salvarComo: String, _ baixando: @escaping (Double) -> Void,
                                   _ organizando: @escaping (Double) -> Void) async throws -> Library {
        let f = Pastas.dados.appendingPathComponent(salvarComo)
        let v = versao(origem, salvarComo)
        if isUrl(origem) && FileManager.default.fileExists(atPath: f.path) && (v.etag != nil || v.lastModified != nil || v.hash != nil) {
            if let l = try await ler(origem, condicional: true, salvarComo, baixando, organizando) { return l }
            baixando(1)
            return try lerArquivo(f, origem, organizando)
        }
        return try await ler(origem, condicional: false, salvarComo, baixando, organizando)!
    }

    // A versão de cada link fica num arquivo ao lado da cópia salva, para valer também depois que o app fecha.
    private static func versao(_ origem: String, _ salvarComo: String) -> Versao {
        trava.lock(); defer { trava.unlock() }
        if let v = versoes[origem] { return v }
        var v = Versao()
        let f = Pastas.dados.appendingPathComponent(salvarComo + ".versao")
        if FileManager.default.fileExists(atPath: Pastas.dados.appendingPathComponent(salvarComo).path),
           let t = try? String(contentsOf: f, encoding: .utf8) {
            let l = t.components(separatedBy: "\n")
            if l.count >= 4 && l[0] == origem { v = Versao(l[1].isEmpty ? nil : l[1], l[2].isEmpty ? nil : l[2], l[3].isEmpty ? nil : l[3]) }
        }
        versoes[origem] = v
        return v
    }

    private static func guardarVersao(_ origem: String, _ salvarComo: String, _ v: Versao) {
        let t = [origem, v.etag ?? "", v.lastModified ?? "", v.hash ?? ""].joined(separator: "\n")
        try? t.write(to: Pastas.dados.appendingPathComponent(salvarComo + ".versao"), atomically: true, encoding: .utf8)
    }

    // Lê um arquivo (link ou arquivo escolhido). Com condicional, devolve nil se não mudou desde a última vez.
    private static func ler(_ origem: String, condicional: Bool, _ salvarComo: String,
                            _ baixando: @escaping (Double) -> Void = { _ in }, _ organizando: @escaping (Double) -> Void = { _ in }) async throws -> Library? {
        let v = versao(origem, salvarComo)
        let tmp = Pastas.cache.appendingPathComponent(salvarComo + ".tmp")
        let destino = Pastas.dados.appendingPathComponent(salvarComo)
        let hash: String
        if isUrl(origem) {
            let tam = (try? FileManager.default.attributesOfItem(atPath: destino.path)[.size] as? NSNumber)?.int64Value ?? 0
            guard let r = try await Baixador.baixar(origem, para: tmp, etag: condicional ? v.etag : nil,
                                                    lastModified: condicional ? v.lastModified : nil, total: tam, andamento: baixando) else { return nil }
            hash = r.hash
            v.etag = r.etag
            v.lastModified = r.lastModified
        } else {
            let d = try Data(contentsOf: arquivoEscolhido, options: .alwaysMapped)
            try? FileManager.default.removeItem(at: tmp)
            try d.write(to: tmp)
            hash = hexDe(Insecure.SHA1.hash(data: d))
        }
        if condicional && hash == v.hash {
            try? FileManager.default.removeItem(at: tmp)
            if isUrl(origem) { guardarVersao(origem, salvarComo, v) }   // mesmo conteúdo: só renova o ETag salvo
            return nil
        }
        baixando(1)
        let lib = try lerArquivo(tmp, isUrl(origem) ? origem : nil, organizando)
        v.hash = hash
        try? FileManager.default.removeItem(at: destino)
        try FileManager.default.moveItem(at: tmp, to: destino)
        if isUrl(origem) { guardarVersao(origem, salvarComo, v) }
        return lib
    }

    private static func lerArquivo(_ f: URL, _ base: String?, _ organizando: @escaping (Double) -> Void) throws -> Library {
        organizando(0)
        let d = try Data(contentsOf: f, options: .alwaysMapped)
        return M3U.parse(d, baseDir: base, progresso: organizando)
    }

    /// Texto para mostrar nos Ajustes: o nome do arquivo escolhido.
    static func nomeAmigavel(_ fonte: String?) -> String {
        guard let f = fonte, f.hasPrefix(prefixoArquivo) else { return "" }
        return "Arquivo do aparelho: " + f.dropFirst(prefixoArquivo.count)
    }
}
