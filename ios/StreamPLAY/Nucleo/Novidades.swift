import Foundation

/// Novidades que o dono escreve no PLAYGER (aba Novidades), igual ao Novidades.kt. O app mostra cada novidade nova uma
/// vez, numa janela ao abrir. Só as dos últimos 45 dias, no máximo 3 por vez.
@MainActor
enum Novidades {
    struct Novidade: Identifiable { let id: String; let titulo: String; let texto: String; let data: Int }

    private static let dias = 45
    private static let max = 3
    static let site = "https://streamplay-site.web.app/#novidades"

    static func novas() async throws -> [Novidade] {
        let (d, h) = try await Rede.pedir("\(Conta.raiz)/novidades?pageSize=100&key=\(Conta.chaveApi)")
        guard (200...299).contains(h.statusCode), let j = jsonObj(d) else { return [] }
        return filtrar(ler(j), Store.s.novidadesVistas, Dia.hoje())
    }

    /// As que ainda não apareceram neste aparelho e são dos últimos 45 dias, da mais nova para a mais antiga.
    static func filtrar(_ todas: [Novidade], _ vistas: [String], _ hoje: Int) -> [Novidade] {
        Array(todas.filter { !vistas.contains($0.id) && Dia.dias($0.data, hoje) <= dias }
            .sorted { $0.data != $1.data ? $0.data > $1.data : $0.id > $1.id }
            .prefix(max))
    }

    static func marcarVistas(_ l: [Novidade]) {
        let st = Store.s
        for n in l where !st.novidadesVistas.contains(n.id) { st.novidadesVistas.append(n.id) }
        if st.novidadesVistas.count > 300 { st.novidadesVistas.removeFirst(st.novidadesVistas.count - 300) }
        st.save()
    }

    static func ler(_ j: [String: Any]) -> [Novidade] {
        guard let docs = j["documents"] as? [[String: Any]] else { return [] }
        return docs.compactMap { d in
            guard let f = d["fields"] as? [String: Any], let nome = d["name"] as? String else { return nil }
            func t(_ n: String) -> String { (Conta.texto(f, n) ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }
            guard let data = Dia.parse(t("data")), !t("titulo").isEmpty else { return nil }
            return Novidade(id: String(nome.split(separator: "/").last ?? ""), titulo: t("titulo"), texto: t("texto"), data: data)
        }
    }

    private static let meses = ["janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro"]

    /// 20261031 -> "31 de outubro de 2026"
    static func dataPorExtenso(_ d: Int) -> String { "\(d % 100) de \(meses[(d / 100) % 100 - 1]) de \(d / 10000)" }
}
