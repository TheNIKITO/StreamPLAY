import SwiftUI

private struct CatCanal: Identifiable {
    let id: String
    let nome: String
    let itens: [Item]
}

/// Aba Canais: os grupos (Todos, Favoritos, Esportes...) ficam em cima; os canais do grupo numa lista com rolagem.
/// No iPad (e no celular deitado), à direita, o painel do canal escolhido: o 1º toque escolhe e o 2º assiste.
/// No celular em pé é só a lista: tocar assiste, segurar favorita.
struct TelaCanais: View {
    let lib: Library
    @Environment(\.medidas) var m
    @ObservedObject private var e = Estado.s
    @ObservedObject private var st = Store.s
    @ObservedObject private var guia = Guia.s
    @State private var sel = "all"
    @State private var escolhido: String?
    @State private var termo = ""
    @State private var achados: [Item]?
    @State private var agora = agoraMs()
    @State private var numeros: [String: Int] = [:]

    private var cats: [CatCanal] {
        var l = [CatCanal(id: "all", nome: "Todos", itens: lib.allChannels)]
        let favs = st.favorites.compactMap { lib.get($0) }.filter { $0.kind == .channel }
        let recentes = st.recent.compactMap { lib.get($0) }.filter { $0.kind == .channel }
        if !favs.isEmpty { l.append(CatCanal(id: "fav", nome: "★ Favoritos", itens: favs)) }
        if !recentes.isEmpty { l.append(CatCanal(id: "recent", nome: "Recentes", itens: recentes)) }
        for c in lib.channels where !c.items.isEmpty { l.append(CatCanal(id: "c:" + c.name, nome: c.name, itens: c.items)) }
        return l
    }

    var body: some View {
        let _ = st.versao
        let cs = cats
        let atual = cs.first { $0.id == sel } ?? cs[0]
        let buscando = !termo.isEmpty
        let lista = buscando ? achados ?? [] : atual.itens
        let titulo = buscando ? "Resultados para \"\(termo)\"" : atual.id == "all" ? "Canais" : atual.nome.replacingOccurrences(of: "★ ", with: "")
        let canal = lista.first { $0.key == escolhido } ?? st.recent.lazy.compactMap { k in lista.first { $0.key == k } }.first ?? lista.first
        let comGuia = guia.versao >= 0 && guia.temAlgum

        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                RoundedRectangle(cornerRadius: 2).fill(Cor.vermelho).frame(width: 4, height: 22)
                Text(titulo).font(.system(size: m.largo ? 24 : 20, weight: .bold)).foregroundColor(.white).lineLimit(1)
                Text(lista.count == 1 ? "1 canal" : "\(lista.count) canais").font(.system(size: 13)).foregroundColor(Cor.dim)
            }
            .padding(.horizontal, m.margem)
            .padding(.top, 14)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 8) {
                    ForEach(cs) { c in
                        ChipGrupo(texto: "\(c.nome)  \(c.itens.count)", selecionado: c.id == sel && !buscando) {
                            sel = c.id
                            e.limparBusca()
                        }
                    }
                }
                .padding(.horizontal, m.margem)
                .padding(.vertical, 12)
            }
            .frame(height: 58)

            if lista.isEmpty {
                Group {
                    if buscando { Mensagem(titulo: "Nenhum canal encontrado", texto: "Tente outro nome.") }
                    else if lib.allChannels.isEmpty {
                        Mensagem(titulo: "Nenhum canal na sua lista ainda",
                                 texto: "Adicione canais no seu arquivo .m3u com group-title=\"CANAIS | Esportes\" (ou só o nome do grupo, ex.: \"Esportes\").")
                    } else { Mensagem(titulo: "Nada por aqui", texto: "") }
                }
                .padding(.horizontal, m.margem)
                .padding(.vertical, 20)
                Spacer()
            } else if m.largo {
                HStack(alignment: .top, spacing: 28) {
                    ListaCanais(lista: lista, numeros: numeros, selecionado: canal?.key, agora: comGuia ? agora : nil) { ch in
                        if ch.key == canal?.key { tocar(ch, lista) } else { escolhido = ch.key }
                    }
                    .frame(width: 360)
                    if let c = canal {
                        PainelCanal(ch: c, agora: comGuia ? agora : nil) { tocar(c, lista) }
                    }
                }
                .padding(.leading, m.margem - 8)
                .padding(.trailing, m.margem)
            } else {
                ListaCanais(lista: lista, numeros: numeros, selecionado: nil, agora: comGuia ? agora : nil) { tocar($0, lista) }
                    .padding(.horizontal, m.margem - 8)
            }
        }
        .onAppear {
            // número fixo de cada canal (a ordem de "Todos"), igual em todos os grupos
            if numeros.isEmpty { var n: [String: Int] = [:]; for (i, c) in lib.allChannels.enumerated() { n[c.key] = i + 1 }; numeros = n }
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                agora = agoraMs()
            }
        }
        .task(id: e.busca) {
            let b = e.busca.trimmingCharacters(in: .whitespaces)
            if b.isEmpty { termo = ""; achados = nil; return }
            try? await Task.sleep(nanoseconds: 260_000_000)
            if Task.isCancelled { return }
            termo = b
            achados = nil
            let todos = lib.allChannels
            let r = await Task.detached(priority: .userInitiated) { () -> [Item] in
                let q = M3U.norm(b)
                var a: [Item] = []
                for c in todos {
                    let n = c.busca ?? M3U.norm(c.name)
                    c.busca = n
                    if n.contains(q) { a.append(c) }
                }
                return a.filter { $0.busca!.hasPrefix(q) } + a.filter { !$0.busca!.hasPrefix(q) }
            }.value
            if !Task.isCancelled { achados = r }
        }
    }

    private func tocar(_ ch: Item, _ lista: [Item]) { e.tocar(PlayRequest.forChannel(ch, lista)) }
}

private struct ChipGrupo: View {
    let texto: String
    let selecionado: Bool
    let acao: () -> Void

    var body: some View {
        Button(action: acao) {
            Text(texto)
                .font(.system(size: 14, weight: .semibold))
                .lineLimit(1)
                .foregroundColor(selecionado ? .white : Color(hex: 0xD8D8E0))
                .padding(.horizontal, 15)
                .padding(.vertical, 7)
                .background(Capsule().fill(selecionado ? Cor.vermelho : Cor.cartao))
                .overlay(Capsule().stroke(selecionado ? Color.clear : Color(hex: 0x2E2E38), lineWidth: 1))
        }
        .buttonStyle(Apertar())
    }
}

private struct ListaCanais: View {
    let lista: [Item]
    let numeros: [String: Int]
    let selecionado: String?
    let agora: Int64?
    let tocar: (Item) -> Void

    var body: some View {
        ScrollViewReader { rolar in
            ScrollView {
                LazyVStack(spacing: 2) {
                    ForEach(Array(lista.enumerated()), id: \.element.key) { i, ch in
                        LinhaCanal(ch: ch, numero: numeros[ch.key] ?? (i + 1), selecionado: ch.key == selecionado,
                                   favorito: Store.s.isFav(ch.key), agora: agora) { tocar(ch) }
                            .id(i)
                    }
                }
                .padding(.vertical, 6)
            }
            .onChange(of: lista.first?.key) { _ in rolar.scrollTo(0, anchor: .top) }
        }
        .background(RoundedRectangle(cornerRadius: 12).fill(Color(hex: 0x121217)))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(hex: 0x1F1F27), lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

private struct LinhaCanal: View {
    let ch: Item
    let numero: Int
    let selecionado: Bool
    let favorito: Bool
    let agora: Int64?
    let acao: () -> Void

    var body: some View {
        Button(action: acao) {
            HStack(spacing: 0) {
                RoundedRectangle(cornerRadius: 2).fill(selecionado ? Cor.vermelho : .clear).frame(width: 3, height: 36)
                Text("\(numero)").font(.system(size: 13)).foregroundColor(Color(hex: 0x6E6E78)).frame(width: 34).padding(.leading, 8)
                Imagem(url: ch.logo, nome: ch.name, crop: false, tamanhoNome: 9, padding: 4, px: 160)
                    .frame(width: 62, height: 36)
                    .background(Cor.cartao)
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                VStack(alignment: .leading, spacing: 1) {
                    Text(ch.name).font(.system(size: 15, weight: .semibold)).foregroundColor(.white).lineLimit(1)
                    if let agora = agora {
                        let ag = Guia.s.agoraESeguir(ch, agora)
                        if let linha = ag.0?.titulo ?? ag.1.map({ "Às \(Guia.hhmm($0.inicio)): \($0.titulo)" }) {
                            Text(linha).font(.system(size: 12)).foregroundColor(Cor.dim).lineLimit(1)
                            if let a = ag.0 { BarraProgresso(fracao: a.progresso(agora), altura: 2).padding(.top, 4) }
                        }
                    }
                }
                .padding(.leading, 12)
                .padding(.trailing, 8)
                Spacer(minLength: 0)
                if favorito { Image(systemName: Icone.estrela).font(.system(size: 14)).foregroundColor(Color(hex: 0xFFC83D)).padding(.trailing, 10) }
            }
            .padding(.vertical, 8)
            .background(RoundedRectangle(cornerRadius: 8).fill(selecionado ? Color(hex: 0x1E1E26) : .clear))
            .contentShape(Rectangle())
        }
        .buttonStyle(Apertar())
        .padding(.horizontal, 6)
        .contextMenu {
            Button { Estado.s.favoritar(ch) } label: {
                if favorito { Label("Remover dos favoritos", systemImage: Icone.estrelaContorno) } else { Label("Favoritar", systemImage: Icone.estrela) }
            }
        }
    }
}

/// Painel do canal escolhido (iPad / deitado): logo, AO VIVO, agora, a seguir, Assistir e Favoritar.
private struct PainelCanal: View {
    let ch: Item
    let agora: Int64?
    let tocar: () -> Void
    @ObservedObject private var st = Store.s

    var body: some View {
        let favorito = st.isFav(ch.key)
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Imagem(url: ch.logo, nome: ch.name, crop: false, tamanhoNome: 22, padding: 20, px: 520)
                    .frame(width: 260, height: 146)
                    .background(Cor.painel)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(hex: 0x26262E), lineWidth: 1))
                HStack(spacing: 0) {
                    HStack(spacing: 0) {
                        Circle().fill(Color.white).frame(width: 7, height: 7)
                        Text("  AO VIVO").font(.system(size: 11, weight: .bold)).foregroundColor(.white)
                    }
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(RoundedRectangle(cornerRadius: 4).fill(Cor.vermelho))
                    if !ch.category.isEmpty { Text("   \(ch.category)").font(.system(size: 14)).foregroundColor(Cor.sub) }
                }
                .padding(.top, 18)
                Text(ch.name).font(.system(size: 30, weight: .bold)).foregroundColor(.white).padding(.top, 8)

                let ag: (Programa?, Programa?) = agora == nil ? (nil, nil) : Guia.s.agoraESeguir(ch, agora!)
                let a = ag.0
                if ag.0 != nil || ag.1 != nil {
                    Text("AGORA").font(.system(size: 12, weight: .bold)).foregroundColor(Cor.dim).padding(.top, 16)
                    Text(a?.titulo ?? "Sem programa agora").font(.system(size: 19, weight: .semibold)).foregroundColor(.white).padding(.top, 3)
                    if let a = a, let agora = agora {
                        HStack(spacing: 0) {
                            BarraProgresso(fracao: a.progresso(agora), altura: 4).frame(width: 280)
                            Text("   \(a.horario)").font(.system(size: 13)).foregroundColor(Cor.sub)
                        }
                        .padding(.top, 8)
                    }
                    if let d = a?.descricao {
                        Text(d).font(.system(size: 14)).foregroundColor(Color(hex: 0xC8C8D0)).lineLimit(3).padding(.top, 10)
                    }
                    let proximos = Guia.s.proximos(ch, agora ?? 0, 5)
                    if !proximos.isEmpty {
                        Text("A SEGUIR").font(.system(size: 12, weight: .bold)).foregroundColor(Cor.dim).padding(.top, 16).padding(.bottom, 4)
                        ForEach(Array(proximos.enumerated()), id: \.offset) { _, p in
                            HStack(spacing: 0) {
                                Text(Guia.hhmm(p.inicio)).font(.system(size: 14)).foregroundColor(Cor.sub).frame(width: 56, alignment: .leading)
                                Text(p.titulo).font(.system(size: 14)).foregroundColor(Color(hex: 0xE4E4EA)).lineLimit(1)
                            }
                            .padding(.vertical, 3)
                        }
                    }
                } else {
                    Text("Sem programação disponível para este canal.").font(.system(size: 14)).foregroundColor(Cor.dim).padding(.top, 16)
                }
                HStack(spacing: 10) {
                    Botao("Assistir", icone: Icone.play, primario: true, acao: tocar)
                    Botao(favorito ? "Favorito" : "Favoritar", icone: favorito ? Icone.estrela : Icone.estrelaContorno) { Estado.s.favoritar(ch) }
                }
                .padding(.top, 22)
                .padding(.bottom, 24)
            }
            .frame(maxWidth: 720, alignment: .leading)
        }
    }
}
