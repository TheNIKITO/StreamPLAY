import Foundation
import UIKit

/// Login dos clientes pelo painel PLAYGER (Firebase), igual ao Conta.kt do Android e ao Conta.cs do PC. Para cada
/// cliente, o painel grava no Firestore o documento acessos/{sha256("playger-acesso|login|senha")}. O app só encontra
/// o documento sabendo login e senha. A data usada é a do servidor.
@MainActor
enum Conta {
    static let projeto = "playger-svect"
    static let chaveApi = "AIzaSyDhFrw8OFoCfSqptvuW1irLmpPgjegBLSo" // chave pública do app web do Firebase (não é segredo)
    static let base = "https://firestore.googleapis.com/v1/projects/\(projeto)/databases/(default)/documents/acessos/"
    /// .../documents
    static var raiz: String { String(base.dropLast("/acessos/".count)) }

    enum Estado { case liberado, bloqueado, invalido, semConexao }

    /// Teste grátis (cadastro pelo site): `fim` em ms (nil = começa ao abrir o app); `cortado` = o aparelho já foi usado
    /// no teste de outra conta; `url` = servidor do PLAYGER que inicia o teste.
    struct Teste { let horas: Double; var fim: Int64?; var cortado: Bool; let bloq: Bool; let url: String? }

    final class Acesso {
        let login, nome, plano, negocio, pix: String?
        let ativo, manual: Bool
        let bloqueiaEm, liberadoAte, faturaVenc: Int?
        let faturaValor: Double?
        /// Limite de aparelhos (0 = sem limite).
        let limite: Int
        /// Link da lista que o painel entrega com o login (vazio = o cliente escolhe no app).
        let lista: String?
        /// Servidor do Pix automático (vazio = desligado no painel).
        let pixUrl: String?
        var teste: Teste?

        init(login: String?, nome: String?, plano: String?, negocio: String?, pix: String?, ativo: Bool, manual: Bool,
             bloqueiaEm: Int?, liberadoAte: Int?, faturaVenc: Int?, faturaValor: Double?, limite: Int, lista: String?,
             pixUrl: String?, teste: Teste?) {
            self.login = login; self.nome = nome; self.plano = plano; self.negocio = negocio; self.pix = pix
            self.ativo = ativo; self.manual = manual; self.bloqueiaEm = bloqueiaEm; self.liberadoAte = liberadoAte
            self.faturaVenc = faturaVenc; self.faturaValor = faturaValor; self.limite = limite; self.lista = lista
            self.pixUrl = pixUrl; self.teste = teste
        }
    }

    /// Pix automático: o QR Code (PNG em base64) e o "copia e cola" do que o cliente deve.
    struct Pix { let pendente: Bool; let valor: Double; let faturas: Int; let copia: String?; let qr: String? }

    /// O servidor do Pix respondeu com um erro (a mensagem dele é para o cliente).
    struct ErroPix: Error { let mensagem: String }

    /// `aparelhos`: bloqueado porque o limite de aparelhos está cheio (não é falta de pagamento).
    struct Resultado {
        let estado: Estado
        let titulo: String
        let mensagem: String
        let acesso: Acesso?
        let hoje: Int
        var aparelhos = false
    }

    static func pix(_ url: String, _ acesso: String) async throws -> Pix {
        let (d, h) = try await Rede.pedir(trimBarra(url) + "/pix", metodo: "POST", corpo: jsonData(["acesso": acesso]),
                                          tipo: "application/json", tempo: 25)
        let j = jsonObj(d) ?? [:]
        func s(_ n: String) -> String? { (j[n] as? String).flatMap { $0.isEmpty ? nil : $0 } }
        if !(200...299).contains(h.statusCode) { throw ErroPix(mensagem: s("erro") ?? "Não foi possível gerar o Pix agora.") }
        return Pix(pendente: j["pendente"] as? Bool ?? false, valor: (j["valor"] as? NSNumber)?.doubleValue ?? 0,
                   faturas: (j["faturas"] as? NSNumber)?.intValue ?? 1, copia: s("copia"), qr: s("qr"))
    }

    static func idAcesso(_ login: String?, _ senha: String) -> String {
        sha256Hex("playger-acesso|" + (login ?? "").trimmingCharacters(in: .whitespaces).lowercased() + "|" + senha)
    }

    static func verificar(_ id: String) async -> Resultado {
        let d: Data, h: HTTPURLResponse
        do {
            (d, h) = try await Rede.pedir("\(base)\(id)?key=\(chaveApi)")
        } catch {
            return Resultado(estado: .semConexao, titulo: "Sem conexão",
                             mensagem: "Não consegui falar com o servidor. Verifique a internet e tente de novo.", acesso: nil, hoje: Dia.hoje())
        }
        let agora = Rede.horaServidor(h)
        let hoje = Dia.de(agora)
        if h.statusCode == 404 {
            return Resultado(estado: .invalido, titulo: "Login ou senha incorretos", mensagem: "Confira o login e a senha que você recebeu.", acesso: nil, hoje: hoje)
        }
        if !(200...299).contains(h.statusCode) {
            return Resultado(estado: .semConexao, titulo: "Servidor indisponível",
                             mensagem: "O servidor não respondeu direito (erro \(h.statusCode)). Tente de novo daqui a pouco.", acesso: nil, hoje: hoje)
        }
        guard let j = jsonObj(d), let a = ler(j) else {
            return Resultado(estado: .semConexao, titulo: "Resposta inválida", mensagem: "Não entendi a resposta do servidor. Tente de novo daqui a pouco.", acesso: nil, hoje: hoje)
        }
        await iniciarTeste(id, a, agora)
        let r = avaliar(a, hoje, agora)
        if r.estado == .liberado { return await conferirAparelhos(id, r) }
        return r
    }

    // ---------- teste grátis (cadastro pelo site; igual ao Conta.kt) ----------

    /// Hash do ID forte deste aparelho (definido ao abrir o app).
    static var idForte: String?

    /// No iPhone o código fica nas Chaves do iOS: não muda ao apagar e instalar o app de novo.
    static func definirIdForte() {
        idForte = sha256Hex("streamplay-aparelho|ios:" + Chaves.codigoFixo())
    }

    private static func iniciarTeste(_ id: String, _ a: Acesso, _ agora: Int64) async {
        guard let t = a.teste, let aparelho = idForte, !t.bloq, a.ativo, let url = t.url, !url.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        if let fim = t.fim, fim <= agora { return }
        guard let resp = try? await Rede.pedir(trimBarra(url) + "/teste", metodo: "POST",
                                                 corpo: jsonData(["acesso": id, "aparelho": aparelho, "nome": nomeAparelho]),
                                                 tipo: "application/json; charset=utf-8", tempo: 20),
              (200...299).contains(resp.1.statusCode), let j = jsonObj(resp.0), j["teste"] as? Bool == true else { return }
        var novo = t
        novo.fim = (j["fim"] as? NSNumber)?.int64Value
        novo.cortado = j["cortado"] as? Bool ?? false
        a.teste = novo
    }

    // ---------- limite de aparelhos (igual ao Conta.kt) ----------

    private static let dia: Int64 = 24 * 60 * 60 * 1000
    private static let inativo = 30 * dia
    private static let renovarCada = dia / 2
    private static let conferirCada = dia / 24
    private static var aparelhosOk: String?
    private static var aparelhosEm: Int64 = 0

    /// Nome e tipo deste aparelho no painel.
    static var nomeAparelho = "iPhone"
    static let tipoAparelho = "celular"

    static func definirAparelho() {
        var u = utsname()
        uname(&u)
        let modelo = withUnsafePointer(to: &u.machine) { $0.withMemoryRebound(to: CChar.self, capacity: 256) { String(cString: $0) } }
        let tipo = UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone"
        nomeAparelho = String((tipo + (modelo.isEmpty ? "" : " – " + modeloAmigavel(modelo))).prefix(80))
    }

    // "iPhone15,2" -> "iPhone 14 Pro" (os mais comuns; os outros ficam com o código)
    private static func modeloAmigavel(_ m: String) -> String {
        let t: [String: String] = [
            "iPhone10,1": "iPhone 8", "iPhone10,4": "iPhone 8", "iPhone10,2": "iPhone 8 Plus", "iPhone10,5": "iPhone 8 Plus",
            "iPhone10,3": "iPhone X", "iPhone10,6": "iPhone X", "iPhone11,8": "iPhone XR", "iPhone11,2": "iPhone XS",
            "iPhone11,6": "iPhone XS Max", "iPhone12,1": "iPhone 11", "iPhone12,3": "iPhone 11 Pro", "iPhone12,5": "iPhone 11 Pro Max",
            "iPhone12,8": "iPhone SE (2ª geração)", "iPhone13,1": "iPhone 12 mini", "iPhone13,2": "iPhone 12", "iPhone13,3": "iPhone 12 Pro",
            "iPhone13,4": "iPhone 12 Pro Max", "iPhone14,4": "iPhone 13 mini", "iPhone14,5": "iPhone 13", "iPhone14,2": "iPhone 13 Pro",
            "iPhone14,3": "iPhone 13 Pro Max", "iPhone14,6": "iPhone SE (3ª geração)", "iPhone14,7": "iPhone 14", "iPhone14,8": "iPhone 14 Plus",
            "iPhone15,2": "iPhone 14 Pro", "iPhone15,3": "iPhone 14 Pro Max", "iPhone15,4": "iPhone 15", "iPhone15,5": "iPhone 15 Plus",
            "iPhone16,1": "iPhone 15 Pro", "iPhone16,2": "iPhone 15 Pro Max", "iPhone17,3": "iPhone 16", "iPhone17,4": "iPhone 16 Plus",
            "iPhone17,1": "iPhone 16 Pro", "iPhone17,2": "iPhone 16 Pro Max", "iPhone17,5": "iPhone 16e",
        ]
        return t[m] ?? m
    }

    struct Aparelho { let id: String; let nome: String; let visto: Int64; let hw: String? }

    private static func conferirAparelhos(_ id: String, _ r: Resultado) async -> Resultado {
        if aparelhosOk == id && agoraMs() - aparelhosEm < conferirCada { return r }
        guard let resp = try? await Rede.pedir("\(base)\(id)/aparelhos?pageSize=300&key=\(chaveApi)"),
              (200...299).contains(resp.1.statusCode) else { return r }
        let (d, h) = resp
        let agora = Rede.horaServidor(h)
        let todos = lerAparelhos(d)
        let eu = Store.s.meuAparelho()
        let meu = todos.first { $0.id == eu }
        let ativos = todos.filter { agora - $0.visto < inativo }
        let limite = r.acesso?.limite ?? 0
        if limite > 0 && !ativos.contains(where: { $0.id == eu }) && ativos.count >= limite {
            let negocio = r.acesso?.negocio ?? ""
            let contato = negocio.trimmingCharacters(in: .whitespaces).isEmpty ? "o suporte" : negocio
            func qtd(_ n: Int) -> String { n == 1 ? "1 aparelho" : "\(n) aparelhos" }
            let nomes = ativos.sorted { $0.visto > $1.visto }.map { $0.nome }.joined(separator: ", ")
            aparelhosOk = nil
            return Resultado(estado: .bloqueado, titulo: "Limite de aparelhos atingido",
                             mensagem: "Este login já está em uso em \(qtd(ativos.count)) (\(nomes)) e o seu plano permite \(qtd(limite)). " +
                                 "Para assistir aqui, peça para \(contato) liberar um aparelho.",
                             acesso: r.acesso, hoje: r.hoje, aparelhos: true)
        }
        if meu == nil || agora - meu!.visto > renovarCada || meu!.nome != nomeAparelho || (idForte != nil && meu!.hw != idForte) {
            if !(await registrar(id, eu)) { return r }   // não conseguiu anotar: confere de novo na próxima
        }
        aparelhosOk = id
        aparelhosEm = agoraMs()
        return r
    }

    // Grava o registro deste aparelho com a hora do servidor (as regras do Firestore só aceitam assim).
    // soSeExiste: só renova um registro que já existe (o sinal de online não recria um aparelho removido no painel).
    // comOnline = false: sem os campos "online" e "hw" (regras antigas do PLAYGER recusam com 403).
    private static func registrar(_ id: String, _ eu: String, online: Bool = true, soSeExiste: Bool = false, comOnline: Bool = true) async -> Bool {
        var campos: [String: Any] = ["nome": ["stringValue": nomeAparelho], "tipo": ["stringValue": tipoAparelho]]
        if comOnline {
            campos["online"] = ["booleanValue": online]
            if let hw = idForte { campos["hw"] = ["stringValue": hw] }
        }
        var escrita: [String: Any] = [
            "update": ["name": "projects/\(projeto)/databases/(default)/documents/acessos/\(id)/aparelhos/\(eu)", "fields": campos],
            "updateTransforms": [["fieldPath": "visto", "setToServerValue": "REQUEST_TIME"]],
        ]
        if soSeExiste { escrita["currentDocument"] = ["exists": true] }
        guard let resp = try? await Rede.pedir("\(raiz):commit?key=\(chaveApi)", metodo: "POST", corpo: jsonData(["writes": [escrita]]),
                                                 tipo: "application/json; charset=utf-8") else { return false }
        let h = resp.1
        if h.statusCode == 403 && comOnline { return await registrar(id, eu, online: online, soSeExiste: soSeExiste, comOnline: false) }
        return (200...299).contains(h.statusCode)
    }

    /// Avisa o painel que este aparelho está (ou deixou de estar) com o app aberto.
    static func sinal(_ id: String, _ online: Bool) async {
        _ = await registrar(id, Store.s.meuAparelho(), online: online, soSeExiste: true)
    }

    static func lerAparelhos(_ d: Data) -> [Aparelho] {
        guard let j = jsonObj(d), let docs = j["documents"] as? [[String: Any]] else { return [] }
        return docs.compactMap { doc in
            guard let f = doc["fields"] as? [String: Any], let nome = doc["name"] as? String else { return nil }
            let visto = ((f["visto"] as? [String: Any])?["timestampValue"] as? String).flatMap(instante) ?? 0
            return Aparelho(id: String(nome.split(separator: "/").last ?? ""), nome: texto(f, "nome") ?? "Aparelho", visto: visto, hw: texto(f, "hw"))
        }
    }

    private static let fmtInstante: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    private static let fmtInstante2 = ISO8601DateFormatter()

    /// "2026-10-27T18:45:17.681Z" em milissegundos.
    static func instante(_ s: String) -> Int64? {
        // o Firestore manda até 9 casas: corta em 3 para o formatador entender
        var t = s
        if let ponto = t.firstIndex(of: "."), let z = t.lastIndex(of: "Z"), ponto < z {
            let frac = t[t.index(after: ponto)..<z]
            t = String(t[..<ponto]) + "." + String((frac + "000").prefix(3)) + "Z"
        }
        guard let d = fmtInstante.date(from: t) ?? fmtInstante2.date(from: t) else { return nil }
        return Int64(d.timeIntervalSince1970 * 1000)
    }

    /// Mesma regra do painel: desativado, bloqueio manual, teste grátis ou fatura vencida além da tolerância.
    static func avaliar(_ a: Acesso, _ hoje: Int, _ agora: Int64 = agoraMs()) -> Resultado {
        let contato = (a.negocio ?? "").trimmingCharacters(in: .whitespaces).isEmpty ? "o suporte" : a.negocio!
        if !a.ativo { return Resultado(estado: .bloqueado, titulo: "Conta desativada", mensagem: "Esta conta está desativada. Fale com \(contato).", acesso: a, hoje: hoje) }
        if a.manual { return Resultado(estado: .bloqueado, titulo: "Acesso bloqueado", mensagem: "Seu acesso está bloqueado. Fale com \(contato).", acesso: a, hoje: hoje) }
        if let t = a.teste {
            guard let fim = t.fim else {
                return Resultado(estado: .semConexao, titulo: "Não consegui iniciar o seu teste", mensagem: "Verifique a internet e tente de novo daqui a pouco.", acesso: a, hoje: hoje)
            }
            if agora < fim { return Resultado(estado: .liberado, titulo: "", mensagem: "", acesso: a, hoje: hoje) }
            let plano = (a.plano ?? "").trimmingCharacters(in: .whitespaces).isEmpty ? "mensal" : a.plano!.lowercased(with: ptBR)
            let motivo = t.cortado ? "Este aparelho já foi usado no teste grátis de outra conta, então o teste desta conta foi encerrado."
                : "Seu teste grátis de \(horas(t.horas)) terminou."
            return Resultado(estado: .bloqueado, titulo: "Seu teste grátis acabou",
                             mensagem: "\(motivo) Para continuar assistindo, pague o Pix de \(dinheiro(a.faturaValor)) (plano \(plano)). Assim que o pagamento for confirmado, o acesso volta na hora.",
                             acesso: a, hoje: hoje)
        }
        let liberado = a.liberadoAte != nil && a.liberadoAte! >= hoje
        if let b = a.bloqueiaEm, hoje >= b && !liberado {
            let msg = (a.faturaVenc != nil ? "Sua fatura de \(dinheiro(a.faturaValor)) venceu em \(Dia.fmt(a.faturaVenc!))." : "Há uma fatura em aberto.") +
                " Assim que o pagamento for confirmado, o acesso volta na hora."
            return Resultado(estado: .bloqueado, titulo: "Acesso bloqueado por falta de pagamento", mensagem: msg, acesso: a, hoje: hoje)
        }
        return Resultado(estado: .liberado, titulo: "", mensagem: "", acesso: a, hoje: hoje)
    }

    /// Lembrete de vencimento para mostrar depois de entrar (ou nil).
    static func aviso(_ r: Resultado?) -> String? {
        if let fim = r?.acesso?.teste?.fim { return "Teste grátis: vai até \(quandoAcaba(fim))." }
        guard let r = r, let v = r.acesso?.faturaVenc else { return nil }
        let valor = dinheiro(r.acesso?.faturaValor)
        let d = Dia.dias(r.hoje, v)
        if d < 0 { return "Sua fatura de \(valor) venceu em \(Dia.fmtCurto(v)). Pague para não perder o acesso." }
        if d == 0 { return "Sua fatura de \(valor) vence hoje." }
        if d <= 3 { return "Sua fatura de \(valor) vence em \(d) dia\(d > 1 ? "s" : "") (\(Dia.fmtCurto(v)))." }
        return nil
    }

    private static let fmtDinheiro: NumberFormatter = {
        let f = NumberFormatter()
        f.numberStyle = .currency
        f.locale = ptBR
        return f
    }()

    static func dinheiro(_ v: Double?) -> String { fmtDinheiro.string(from: NSNumber(value: v ?? 0)) ?? "R$ 0,00" }

    private static func horas(_ h: Double) -> String {
        if h == h.rounded(.down) { let n = Int(h); return "\(n) hora\(n == 1 ? "" : "s")" }
        return String(format: "%.1f", h).replacingOccurrences(of: ".", with: ",") + " horas"
    }

    /// "hoje às 21:40", "amanhã às 02:10" ou "05/11 às 21:40" (no horário do aparelho).
    static func quandoAcaba(_ fim: Int64, _ agora: Int64 = agoraMs()) -> String {
        let c = Calendar.current.dateComponents([.day, .month, .hour, .minute], from: Date(timeIntervalSince1970: Double(fim) / 1000))
        let dias = Dia.dias(Dia.de(agora), Dia.de(fim))
        let dia = dias == 0 ? "hoje" : dias == 1 ? "amanhã" : String(format: "%02d/%02d", c.day!, c.month!)
        return dia + String(format: " às %02d:%02d", c.hour!, c.minute!)
    }

    // ---------- leitura do documento no formato da API REST do Firestore ----------

    static func ler(_ j: [String: Any]) -> Acesso? {
        guard let f = j["fields"] as? [String: Any] else { return nil }
        let fatura = ((f["fatura"] as? [String: Any])?["mapValue"] as? [String: Any])?["fields"] as? [String: Any]
        let teste = (((f["teste"] as? [String: Any])?["mapValue"] as? [String: Any])?["fields"] as? [String: Any]).map { t in
            Teste(horas: numero(t, "horas") ?? 0, fim: numero(t, "fim").map { Int64($0) }, cortado: !(texto(t, "cortado") ?? "").isEmpty,
                  bloq: bool(t, "bloq"), url: texto(t, "url"))
        }
        return Acesso(
            login: texto(f, "login"), nome: texto(f, "nome"), plano: texto(f, "plano"), negocio: texto(f, "negocio"), pix: texto(f, "pix"),
            ativo: bool(f, "ativo"),
            // conta de teste: "manual" vem true só para os apps antigos; o bloqueio de verdade é o teste.bloq
            manual: teste?.bloq ?? bool(f, "manual"),
            bloqueiaEm: Dia.parse(texto(f, "bloqueiaEm")), liberadoAte: Dia.parse(texto(f, "liberadoAte")),
            faturaVenc: fatura.flatMap { Dia.parse(texto($0, "venc")) }, faturaValor: fatura.flatMap { numero($0, "valor") },
            limite: Int(numero(f, "limite") ?? 0), lista: texto(f, "lista"), pixUrl: texto(f, "pixUrl"), teste: teste)
    }

    static func texto(_ f: [String: Any], _ n: String) -> String? { (f[n] as? [String: Any])?["stringValue"] as? String }
    static func bool(_ f: [String: Any], _ n: String) -> Bool { (f[n] as? [String: Any])?["booleanValue"] as? Bool ?? false }
    static func numero(_ f: [String: Any], _ n: String) -> Double? {
        guard let v = f[n] as? [String: Any] else { return nil }
        if let d = v["doubleValue"] as? NSNumber { return d.doubleValue }
        if let i = v["integerValue"] as? String { return Double(i) }
        if let i = v["integerValue"] as? NSNumber { return i.doubleValue }
        return nil
    }

    static func trimBarra(_ s: String) -> String {
        var t = s
        while t.hasSuffix("/") { t.removeLast() }
        return t
    }
}
