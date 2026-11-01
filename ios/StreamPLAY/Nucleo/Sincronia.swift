import Foundation

/// Sincroniza o "continuar assistindo" e a Minha lista entre os aparelhos da mesma conta (PC, TV, celular) num
/// documento só no Firestore: acessos/{id}/sync/dados = { p: {chave: {pos, dur, at, show}}, f: {chave: {on, at}}, l, at }
/// (l = quando alguém usou "Limpar continuar assistindo"). Vale o mais recente de cada item. Igual ao Sincronia.kt.
@MainActor
enum Sincronia {
    private static let maxProgresso = 40   // na nuvem só os mais recentes (o aparelho guarda todos)
    private static let maxFavoritos = 300
    private static var ocupado = false

    /// Mudou progresso ou Minha lista aqui e a nuvem ainda não tem.
    static var sujo = false

    struct Nuvem { let p: [String: Progresso]; let f: [String: FavMarca]; let limpo: Int64 }

    /// Lê a nuvem e junta com o que está aqui. Devolve true se algo mudou neste aparelho.
    static func baixar(_ id: String) async -> Bool {
        if ocupado { return false }
        ocupado = true
        defer { ocupado = false }
        guard let n = await ler(id) else { return false }
        return juntar(n)
    }

    /// Junta com a nuvem (para não apagar o que outro aparelho mandou) e grava. Só faz algo se estiver sujo.
    static func enviar(_ id: String) async -> Bool {
        if !sujo || ocupado { return false }
        ocupado = true
        defer { ocupado = false }
        sujo = false
        guard let n = await ler(id) else { sujo = true; return false }
        let mudou = juntar(n)
        sujo = false   // a junção pode marcar "falta subir": é o que vai agora
        if !(await gravar(montar(id))) { sujo = true }
        return mudou
    }

    // nil = não deu para ler; vazio = ainda não existe nada na nuvem
    private static func ler(_ id: String) async -> Nuvem? {
        guard let resp = try? await Rede.pedir("\(Conta.base)\(id)/sync/dados?key=\(Conta.chaveApi)") else { return nil }
        let (d, h) = resp
        if h.statusCode == 404 { return Nuvem(p: [:], f: [:], limpo: 0) }
        guard (200...299).contains(h.statusCode), let j = jsonObj(d) else { return nil }
        return lerJson(j)
    }

    static func lerJson(_ j: [String: Any]) -> Nuvem {
        guard let campos = j["fields"] as? [String: Any] else { return Nuvem(p: [:], f: [:], limpo: 0) }
        var p: [String: Progresso] = [:]
        mapa(campos, "p") { k, f in
            p[k] = Progresso(pos: num(f, "pos"), dur: num(f, "dur"), at: num(f, "at"), show: Conta.texto(f, "show").flatMap { $0.isEmpty ? nil : $0 })
        }
        var fav: [String: FavMarca] = [:]
        mapa(campos, "f") { k, f in fav[k] = FavMarca(on: Conta.bool(f, "on"), at: num(f, "at")) }
        return Nuvem(p: p, f: fav, limpo: num(campos, "l"))
    }

    private static func mapa(_ campos: [String: Any], _ nome: String, _ cada: (String, [String: Any]) -> Void) {
        guard let itens = ((campos[nome] as? [String: Any])?["mapValue"] as? [String: Any])?["fields"] as? [String: Any] else { return }
        for (k, v) in itens {
            if let f = ((v as? [String: Any])?["mapValue"] as? [String: Any])?["fields"] as? [String: Any] { cada(k, f) }
        }
    }

    private static func num(_ f: [String: Any], _ n: String) -> Int64 {
        let v = (f[n] as? [String: Any])?["integerValue"]
        if let s = v as? String { return Int64(s) ?? 0 }
        return (v as? NSNumber)?.int64Value ?? 0
    }

    /// Junta a nuvem no Store (vale o mais recente de cada item). Devolve true se algo daqui mudou.
    static func juntar(_ n: Nuvem) -> Bool {
        let st = Store.s
        var mudou = false
        if n.limpo > st.progressoLimpo {
            st.progressoLimpo = n.limpo
            st.progress = st.progress.filter { $0.value.at > n.limpo }
            mudou = true
        } else if n.limpo < st.progressoLimpo { sujo = true }
        for (k, c) in n.p {
            if c.at <= st.progressoLimpo || c.dur <= 0 { continue }
            if let aqui = st.progress[k], aqui.at >= c.at { if aqui.at > c.at { sujo = true }; continue }
            st.progress[k] = c
            mudou = true
        }
        // tem aqui e não tem lá: a nuvem precisa receber
        if st.progress.sorted(by: { $0.value.at > $1.value.at }).prefix(maxProgresso).contains(where: { n.p[$0.key] == nil }) { sujo = true }

        for (k, c) in n.f {
            if let aqui = st.favLog[k], aqui.at >= c.at { if aqui.at > c.at { sujo = true }; continue }
            st.favLog[k] = c
            let tem = st.favorites.contains(k)
            if c.on && !tem { st.favorites.insert(k, at: 0); mudou = true }
            else if !c.on && tem { st.favorites.removeAll { $0 == k }; mudou = true }
        }
        // favoritos daqui que a nuvem não conhece (inclusive os de antes da sincronização existir)
        if st.favorites.contains(where: { n.f[$0] == nil }) || st.favLog.keys.contains(where: { n.f[$0] == nil }) { sujo = true }
        if mudou { st.save() }
        return mudou
    }

    private static func montar(_ id: String) -> Data {
        let st = Store.s
        var p: [String: Any] = [:]
        for (k, v) in st.progress.sorted(by: { $0.value.at > $1.value.at }).prefix(maxProgresso) {
            var c: [String: Any] = ["pos": ["integerValue": String(v.pos)], "dur": ["integerValue": String(v.dur)], "at": ["integerValue": String(v.at)]]
            if let s = v.show { c["show"] = ["stringValue": s] }
            p[k] = ["mapValue": ["fields": c]]
        }
        // favoritos ligados (sem marca de hora = de antes da sincronização, valem como antigos) + os tirados mais recentes
        var marcas = st.favLog
        for k in st.favorites where marcas[k] == nil { marcas[k] = FavMarca(on: true, at: 1) }
        var f: [String: Any] = [:]
        let ordem = marcas.sorted { $0.value.on != $1.value.on ? $0.value.on : $0.value.at > $1.value.at }
        for (k, v) in ordem.prefix(maxFavoritos) {
            f[k] = ["mapValue": ["fields": ["on": ["booleanValue": v.on], "at": ["integerValue": String(v.at)]]]]
        }
        let campos: [String: Any] = [
            "p": ["mapValue": ["fields": p]], "f": ["mapValue": ["fields": f]], "l": ["integerValue": String(st.progressoLimpo)],
        ]
        let escrita: [String: Any] = [
            "update": ["name": "projects/\(Conta.projeto)/databases/(default)/documents/acessos/\(id)/sync/dados", "fields": campos],
            "updateTransforms": [["fieldPath": "at", "setToServerValue": "REQUEST_TIME"]],
        ]
        return jsonData(["writes": [escrita]])
    }

    private static func gravar(_ corpo: Data) async -> Bool {
        guard let resp = try? await Rede.pedir("\(Conta.raiz):commit?key=\(Conta.chaveApi)", metodo: "POST", corpo: corpo,
                                               tipo: "application/json; charset=utf-8") else { return false }
        return (200...299).contains(resp.1.statusCode)
    }
}
