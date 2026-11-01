import Foundation
import CryptoKit

/// 1:02:03 / 4:05
func tempo(_ ms0: Int64) -> String {
    let s = max(0, ms0) / 1000
    let h = s / 3600
    let m = (s / 60) % 60
    let ss = s % 60
    return h >= 1 ? String(format: "%d:%02d:%02d", h, m, ss) : String(format: "%d:%02d", m, ss)
}

/// "Faltam 1h 20min" / "Faltam 5min"
func faltam(_ ms: Int64) -> String {
    let s = max(0, ms) / 1000
    let h = s / 3600
    let m = (s / 60) % 60
    return h >= 1 ? "Faltam \(h)h \(m)min" : "Faltam \(max(1, m))min"
}

/// Milissegundos desde 1970 (UTC), como no Android/PC.
func agoraMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

/// Relógio que só anda para a frente (para medir intervalos), em ms.
func relogio() -> Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1000) }

let ptBR = Locale(identifier: "pt_BR")

func hexDe(_ d: some Sequence<UInt8>) -> String { d.map { String(format: "%02x", $0) }.joined() }

func sha256Hex(_ s: String) -> String { hexDe(SHA256.hash(data: Data(s.utf8))) }

/// Datas sem hora no formato aaaammdd (iguais às do Android e do PC).
enum Dia {
    private static let cal: Calendar = { var c = Calendar(identifier: .gregorian); c.timeZone = .current; return c }()
    private static let utc: Calendar = { var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: "UTC")!; return c }()

    static func de(_ ms: Int64) -> Int {
        let c = cal.dateComponents([.year, .month, .day], from: Date(timeIntervalSince1970: Double(ms) / 1000))
        return c.year! * 10000 + c.month! * 100 + c.day!
    }

    static func hoje() -> Int { de(agoraMs()) }

    /// "2026-10-25" -> 20261025
    static func parse(_ s: String?) -> Int? {
        guard let s = s, s.count == 10 else { return nil }
        let p = s.split(separator: "-")
        guard p.count == 3, p[0].count == 4, p[1].count == 2, p[2].count == 2,
              let a = Int(p[0]), let m = Int(p[1]), let d = Int(p[2]), (1...12).contains(m), (1...31).contains(d) else { return nil }
        return a * 10000 + m * 100 + d
    }

    private static func epoca(_ d: Int) -> Int {
        let data = utc.date(from: DateComponents(year: d / 10000, month: (d / 100) % 100, day: d % 100))!
        return Int(data.timeIntervalSince1970 / 86400)
    }

    /// Quantos dias de `de` até `ate` (negativo se já passou).
    static func dias(_ de: Int, _ ate: Int) -> Int { epoca(ate) - epoca(de) }

    static func fmt(_ d: Int) -> String { String(format: "%02d/%02d/%04d", d % 100, (d / 100) % 100, d / 10000) }
    static func fmtCurto(_ d: Int) -> String { String(format: "%02d/%02d", d % 100, (d / 100) % 100) }
}

/// Erro HTTP com o código (404, 500...).
struct ErroHttp: Error { let codigo: Int }

/// Mensagem simples para o cliente a partir de um erro de rede/arquivo.
func mensagemErro(_ e: Error) -> String {
    if let h = e as? ErroHttp { return "o servidor respondeu com erro \(h.codigo)" }
    let n = e as NSError
    if n.domain == NSURLErrorDomain {
        switch n.code {
        case NSURLErrorNotConnectedToInternet, NSURLErrorCannotFindHost, NSURLErrorDNSLookupFailed, NSURLErrorBadURL, NSURLErrorUnsupportedURL:
            return "sem internet ou endereço inválido"
        case NSURLErrorTimedOut:
            return "o servidor demorou demais para responder"
        default:
            return "falha de conexão (\(n.code))"
        }
    }
    if n.domain == NSCocoaErrorDomain && (n.code == NSFileReadNoSuchFileError || n.code == NSFileNoSuchFileError) { return "arquivo não encontrado" }
    return n.localizedDescription
}

/// Pedido HTTP simples (sem cache: o app decide quando baixar de novo). Devolve corpo, resposta e a hora do servidor.
enum Rede {
    static let sessao: URLSessionConfiguration = {
        let c = URLSessionConfiguration.ephemeral
        c.urlCache = nil
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.timeoutIntervalForRequest = 20
        c.httpAdditionalHeaders = ["User-Agent": "StreamPLAY/1.0 (VLC; iOS)"]
        return c
    }()
    static let s = URLSession(configuration: sessao)

    static func pedir(_ url: String, metodo: String = "GET", corpo: Data? = nil, tipo: String? = nil, tempo: TimeInterval = 15,
                      cabecalhos: [String: String] = [:]) async throws -> (Data, HTTPURLResponse) {
        guard let u = URL(string: url) else { throw URLError(.badURL) }
        var r = URLRequest(url: u, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: tempo)
        r.httpMethod = metodo
        r.httpBody = corpo
        if let tipo = tipo { r.setValue(tipo, forHTTPHeaderField: "Content-Type") }
        for (k, v) in cabecalhos { r.setValue(v, forHTTPHeaderField: k) }
        let (d, resp) = try await s.data(for: r)
        guard let h = resp as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        return (d, h)
    }

    private static let fmtData: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "GMT")
        f.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        return f
    }()

    /// Hora do servidor (cabeçalho Date), em ms; sem ele, a do aparelho.
    static func horaServidor(_ h: HTTPURLResponse) -> Int64 {
        if let d = h.value(forHTTPHeaderField: "Date"), let t = fmtData.date(from: d) { return Int64(t.timeIntervalSince1970 * 1000) }
        return agoraMs()
    }
}

extension Dictionary where Key == String, Value == Any {
    func obj(_ k: String) -> [String: Any]? { self[k] as? [String: Any] }
    func arr(_ k: String) -> [Any]? { self[k] as? [Any] }
    func str(_ k: String) -> String? { self[k] as? String }
}

func jsonObj(_ d: Data) -> [String: Any]? { (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] }

func jsonData(_ o: Any) -> Data { (try? JSONSerialization.data(withJSONObject: o)) ?? Data() }
