import SwiftUI

private struct Grade { let titulo: String; let itens: [Item] }

private struct Linha: Identifiable {
    let titulo: String
    let cartoes: [Cartao]
    let total: Int
    let todos: [Item]?
    var continuar = false
    var id: String { titulo }
}

/// Fileira de categoria; na aba Início o título diz se é de filmes ou de séries.
private struct LinhaCat: Identifiable {
    let titulo: String
    let cat: Category
    var id: String { titulo + "|" + cat.name }
}

private func linhasDe(_ lib: Library, _ kind: Kind?) -> [LinhaCat] {
    if let k = kind { return lib.cats(k).filter { !$0.items.isEmpty }.map { LinhaCat(titulo: $0.name, cat: $0) } }
    // Início: lançamentos de filmes e de séries, depois as primeiras categorias de cada um, alternadas
    let f = lib.cats(.movie).filter { !$0.items.isEmpty }
    let s = lib.cats(.series).filter { !$0.items.isEmpty }
    var l: [LinhaCat] = []
    for c in f where c.isRelease { l.append(LinhaCat(titulo: "Filmes em lançamento", cat: c)) }
    for c in s where c.isRelease { l.append(LinhaCat(titulo: "Séries em lançamento", cat: c)) }
    let fr = Array(f.filter { !$0.isRelease }.prefix(6))
    let sr = Array(s.filter { !$0.isRelease }.prefix(6))
    for i in 0..<max(fr.count, sr.count) {
        if i < fr.count { l.append(LinhaCat(titulo: "Filmes • \(fr[i].name)", cat: fr[i])) }
        if i < sr.count { l.append(LinhaCat(titulo: "Séries • \(sr[i].name)", cat: sr[i])) }
    }
    return l
}

@MainActor
private func favoritos(_ lib: Library, _ kind: Kind?) -> [Item] {
    Store.s.favorites.compactMap { lib.get($0) }.filter { $0.kind != .channel && (kind == nil || $0.kind == kind) }
}

@MainActor
private func cartao(_ i: Item, naFileiraDeLancamentos: Bool = false) -> Cartao {
    Cartao(item: i, progress: i.kind == .movie ? Store.s.watched(i.key) : 0, isNew: i.isRelease && !naFileiraDeLancamentos)
}

private func buscar(_ lib: Library, _ kind: Kind?, _ termo: String) -> [Item] {
    let q = M3U.norm(termo)
    let pool = kind == nil ? lib.allMovies + lib.allSeries : lib.all(kind!)
    var achados: [Item] = []
    for i in pool {
        let n = i.busca ?? M3U.norm(i.name)
        i.busca = n
        if n.contains(q) { achados.append(i) }
    }
    return achados.filter { $0.busca!.hasPrefix(q) } + achados.filter { !$0.busca!.hasPrefix(q) }
}

/// Página de Filmes, Séries, Início (inicio: filmes e séries juntos) ou Minha lista (kind = nil).
struct TelaNavegar: View {
    let lib: Library
    let kind: Kind?
    var inicio = false

    @ObservedObject private var e = Estado.s
    @ObservedObject private var st = Store.s
    @State private var grade: Grade?
    @State private var escolherCategoria = false
    @State private var termo = ""
    @State private var resultados: [Item]?

    var body: some View {
        ZStack {
            if !termo.isEmpty {
                GradeItens(titulo: "Resultados para \"\(termo)\"", itens: resultados ?? [], voltar: { e.limparBusca() },
                           vazio: ("Nada encontrado para \"\(termo)\"", "Confira a ortografia ou tente outro nome."), carregando: resultados == nil)
            } else if kind == nil && !inicio {
                GradeItens(titulo: "Minha lista", itens: favoritos(lib, nil), voltar: nil,
                           vazio: ("Sua lista está vazia", "Abra um filme ou série e toque no botão + (ou segure o cartão) para guardar aqui."))
            } else if let g = grade {
                GradeItens(titulo: g.titulo, itens: g.itens, voltar: { grade = nil }, vazio: ("Nada por aqui", ""))
            } else {
                Feed(lib: lib, kind: kind, aoCategorias: { escolherCategoria = true }, verTudo: { t, l in grade = Grade(titulo: t, itens: l) })
            }
            if escolherCategoria, let k = kind {
                JanelaCategorias(cats: lib.cats(k).filter { !$0.items.isEmpty }) { c in
                    escolherCategoria = false
                    if let c = c { grade = Grade(titulo: c.name, itens: c.items) }
                }
            }
        }
        .onChange(of: e.voltarAoInicio) { _ in grade = nil }
        // a busca só filtra depois que a pessoa para de digitar
        .task(id: e.busca) {
            let b = e.busca.trimmingCharacters(in: .whitespaces)
            if b.isEmpty { termo = ""; resultados = nil; return }
            try? await Task.sleep(nanoseconds: 260_000_000)
            if Task.isCancelled { return }
            termo = b
            resultados = nil
            let lib = self.lib, kind = self.kind
            let r = await Task.detached(priority: .userInitiated) { buscar(lib, kind, b) }.value
            if !Task.isCancelled { resultados = r }
        }
    }
}

// ---------- Feed (destaque + fileiras) ----------

private struct Feed: View {
    let lib: Library
    let kind: Kind?
    let aoCategorias: () -> Void
    let verTudo: (String, [Item]) -> Void

    @Environment(\.medidas) var m
    @ObservedObject private var st = Store.s
    @State private var cats: [LinhaCat] = []
    @State private var pool: [Item] = []
    @State private var pronto = false

    var body: some View {
        Group {
            if pronto && cats.isEmpty {
                VStack(alignment: .leading) {
                    if kind == nil {
                        Mensagem(titulo: "Nenhum filme ou série na sua lista ainda",
                                 texto: "Os filmes e séries do seu arquivo .m3u aparecem aqui. Os canais ficam na aba Canais.")
                    } else {
                        Mensagem(titulo: kind == .movie ? "Nenhum filme na sua lista ainda" : "Nenhuma série na sua lista ainda",
                                 texto: kind == .movie
                                    ? "Adicione filmes no seu arquivo .m3u com group-title=\"FILMES\" (ou \"FILMES | Lançamentos\") — o StreamPLAY atualiza sozinho."
                                    : "Adicione episódios no seu arquivo .m3u com group-title=\"SERIES\" e o nome no formato \"Nome da Série S01E01\". O StreamPLAY organiza por temporada sozinho.")
                    }
                    Spacer()
                }
                .padding(.top, 60)
                .padding(.horizontal, m.margem)
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        if !pool.isEmpty { Destaque(pool: pool, kind: kind, mostrarCategorias: kind != nil && cats.count > 1, aoCategorias: aoCategorias) }
                        let _ = st.versao
                        let cont = Resume.continueCards(lib, kind)
                        if !cont.isEmpty { Fileira(titulo: "Continuar assistindo", cartoes: cont, total: cont.count, verTudo: nil, continuar: true) }
                        let favs = favoritos(lib, kind)
                        if !favs.isEmpty {
                            Fileira(titulo: "Minha lista", cartoes: favs.prefix(40).map { cartao($0) }, total: favs.count, verTudo: { verTudo("Minha lista", favs) })
                        }
                        ForEach(cats) { l in
                            Fileira(titulo: l.titulo, cartoes: l.cat.items.prefix(40).map { cartao($0, naFileiraDeLancamentos: l.cat.isRelease) },
                                    total: l.cat.items.count, verTudo: { verTudo(l.titulo, l.cat.items) })
                        }
                    }
                    .padding(.bottom, 48)
                }
            }
        }
        .onAppear {
            if pronto { return }
            cats = linhasDe(lib, kind)
            let todos = kind == nil ? lib.allMovies + lib.allSeries : lib.all(kind!)
            var vistos = Set<Item>()
            let lanc = cats.filter { $0.cat.isRelease }.flatMap { $0.cat.items }.filter { vistos.insert($0).inserted }
            var p = Array((lanc.isEmpty ? todos : lanc).lazy.filter { !($0.logo ?? "").isEmpty || $0.hasBackdrop }.prefix(15))
            if p.isEmpty { p = Array(todos.prefix(15)) }
            pool = p
            pronto = true
        }
    }
}

private struct Destaque: View {
    let pool: [Item]
    let kind: Kind?
    let mostrarCategorias: Bool
    let aoCategorias: () -> Void

    @Environment(\.medidas) var m
    @ObservedObject private var e = Estado.s
    @ObservedObject private var st = Store.s
    @State private var indice = -1

    var body: some View {
        let altura = m.largo ? min(max(min(m.largura * 0.42, m.altura * 0.7), 260), 600) : min(max(m.largura * 1.2, 420), 600)
        let item = pool[indice >= 0 && indice < pool.count ? indice : 0]
        ZStack(alignment: .bottomLeading) {
            FundoDestaque(item: item, altura: altura)
                .id(item.key)
                .transition(.opacity.animation(.easeInOut(duration: 0.7)))
            LinearGradient(stops: [.init(color: Cor.fundo.opacity(0.95), location: 0), .init(color: Cor.fundo.opacity(0.75), location: 0.36),
                                   .init(color: .clear, location: 0.72)], startPoint: .leading, endPoint: .trailing)
            LinearGradient(stops: [.init(color: .clear, location: 0.5), .init(color: Cor.fundo, location: 1)], startPoint: .top, endPoint: .bottom)

            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 18) {
                    if let k = kind { Text(k == .movie ? "Filmes" : "Séries").font(.system(size: m.largo ? 28 : 22, weight: .bold)).foregroundColor(.white) }
                    if mostrarCategorias {
                        Button(action: aoCategorias) {
                            HStack(spacing: 4) {
                                Text("Categorias").font(.system(size: 14, weight: .semibold))
                                Image(systemName: "chevron.down").font(.system(size: 12, weight: .semibold))
                            }
                            .foregroundColor(.white)
                            .padding(.leading, 12).padding(.trailing, 8).padding(.vertical, 6)
                            .background(RoundedRectangle(cornerRadius: 6).fill(Cor.fundo.opacity(0.8)))
                            .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color.white.opacity(0.4), lineWidth: 1))
                        }
                        .buttonStyle(Apertar())
                    }
                }
                .padding(.top, 14)
                Spacer(minLength: 0)
                VStack(alignment: .leading, spacing: 0) {
                    RotuloTipo(texto: item.kindLabel)
                    tituloDestaque(item.name)
                        .font(.system(size: m.largo ? 44 : 30, weight: .black))
                        .lineLimit(2)
                        .padding(.vertical, 8)
                    HStack(spacing: 12) {
                        if item.isRelease { Selo(texto: "LANÇAMENTO") }
                        Text(item.meta).font(.system(size: 14)).foregroundColor(Color(hex: 0xC8C8D0)).lineLimit(1)
                    }
                    if let p = item.plot, !p.trimmingCharacters(in: .whitespaces).isEmpty {
                        Text(p).font(.system(size: m.largo ? 16 : 14)).foregroundColor(.white).lineLimit(m.largo ? 3 : 2).padding(.top, 10)
                    }
                    HStack(spacing: 10) {
                        let _ = st.versao
                        Botao(textoAssistir(item), icone: Icone.play, primario: true) { e.tocar(PlayRequest.forItem(item)) }
                        Botao(m.largo ? "Mais informações" : "Detalhes", icone: Icone.info) { e.abrirDetalhes(item) }
                    }
                    .padding(.top, 16)
                }
                .frame(maxWidth: 640, alignment: .leading)
                .padding(.bottom, 20)
            }
            .padding(.horizontal, m.margem)
        }
        .frame(height: altura)
        .frame(maxWidth: .infinity)
        .clipped()
        .contentShape(Rectangle())
        .onTapGesture { e.abrirDetalhes(item) }
        .onAppear { if indice < 0 { indice = Int.random(in: 0..<pool.count) } }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 11_000_000_000)
                if pool.count >= 2 && e.player == nil && e.detalhes == nil {
                    withAnimation { indice = (max(0, indice) + 1) % pool.count }
                }
            }
        }
    }

    /// "This Is Going to" + "Hurt" em vermelho; com uma palavra só, fica tudo branco.
    private func tituloDestaque(_ nome: String) -> Text {
        let n = nome.trimmingCharacters(in: .whitespaces)
        guard let i = n.lastIndex(of: " "), i > n.startIndex else { return Text(n).foregroundColor(.white) }
        return Text(n[...i]).foregroundColor(.white) + Text(n[n.index(after: i)...]).foregroundColor(Cor.vermelhoClaro)
    }

    private func textoAssistir(_ item: Item) -> String {
        if item.kind == .movie { return Store.s.watched(item.key) > 0 ? "Continuar" : "Assistir" }
        guard let ep = Resume.episodeFor(item) else { return "Assistir" }
        return Store.s.getProgress(ep.key) != nil || ep != item.episodes.first ? "Continuar \(ep.label)" : "Assistir"
    }
}

struct FundoDestaque: View {
    let item: Item
    let altura: CGFloat
    @Environment(\.medidas) var m

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .trailing) {
                if item.hasBackdrop {
                    Imagem(url: item.backdrop, nome: item.name, px: 1280).frame(width: g.size.width, height: g.size.height)
                } else {
                    // sem imagem larga: a capa desfocada no fundo e a capa inteira do lado
                    Imagem(url: item.logo, nome: item.name, tamanhoNome: 1, px: 40)
                        .frame(width: g.size.width, height: g.size.height)
                        .blur(radius: 12)
                        .opacity(0.45)
                    if m.largo {
                        Imagem(url: item.logo, nome: item.name, tamanhoNome: 22, px: 600)
                            .frame(width: altura * 0.72 * 2 / 3, height: altura * 0.72)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Cor.linha, lineWidth: 1))
                            .padding(.trailing, m.margem * 2)
                            .padding(.top, 40)
                    }
                }
            }
        }
    }
}

// ---------- Fileiras ----------

private struct Fileira: View {
    let titulo: String
    let cartoes: [Cartao]
    let total: Int
    let verTudo: (() -> Void)?
    var continuar = false
    @Environment(\.medidas) var m

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                TituloSecao(texto: titulo, tamanho: m.largo ? 20 : 17)
                Spacer()
                if let v = verTudo, total > 6 {
                    Button(action: v) {
                        Text("Ver tudo ›").font(.system(size: 14, weight: .semibold)).foregroundColor(Cor.link).padding(.horizontal, 8).padding(.vertical, 3)
                    }
                }
            }
            .padding(.horizontal, m.margem)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 8) {
                    ForEach(cartoes) { c in
                        if continuar { CartaoContinuar(c: c).frame(width: m.cartao) } else { CartaoPoster(c: c).frame(width: m.cartao) }
                    }
                    if let v = verTudo, total > cartoes.count {
                        Button(action: v) {
                            Text("Ver todos\n(\(total))").font(.system(size: 15, weight: .semibold)).foregroundColor(.white).multilineTextAlignment(.center)
                                .frame(width: m.cartao, height: m.cartao * 1.5)
                                .background(RoundedRectangle(cornerRadius: 6).fill(Cor.cartao))
                        }
                        .buttonStyle(Apertar())
                    }
                }
                .padding(.horizontal, m.margem)
                .padding(.vertical, 10)
            }
        }
        .padding(.top, 16)
    }
}

/// Menu de segurar o cartão: guardar na Minha lista.
private struct MenuFavorito: ViewModifier {
    let item: Item

    func body(content: Content) -> some View {
        content.contextMenu {
            Button { Estado.s.favoritar(item) } label: {
                if Store.s.isFav(item.key) { Label("Remover da Minha lista", systemImage: "checkmark") }
                else { Label("Adicionar à Minha lista", systemImage: "plus") }
            }
            Button { Estado.s.abrirDetalhes(item) } label: { Label("Mais informações", systemImage: Icone.info) }
        }
    }
}

/// Capa em pé, com o nome embaixo.
struct CartaoPoster: View {
    let c: Cartao

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Button { Estado.s.abrirCartao(c) } label: {
                Color.clear
                    .aspectRatio(2 / 3, contentMode: .fit)
                    .overlay(Imagem(url: c.item.logo, nome: c.item.name))
                    .overlay(alignment: .topLeading) { if c.isNew { Selo(texto: "NOVO").padding(6) } }
                    .overlay(alignment: .bottom) { if c.hasProgress { BarraProgresso(fracao: c.progress).padding(6) } }
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                    .contentShape(RoundedRectangle(cornerRadius: 6))
            }
            .buttonStyle(Apertar())
            .modifier(MenuFavorito(item: c.item))
            Text(c.sub ?? c.item.name).font(.system(size: 12)).foregroundColor(Cor.sub).lineLimit(1).padding(.horizontal, 2)
        }
    }
}

/// Continuar assistindo: capa em pé, nome, "T1 • E4" (ou quanto falta) e a barra de progresso embaixo.
private struct CartaoContinuar: View {
    let c: Cartao

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button { Estado.s.abrirCartao(c) } label: {
                Color.clear
                    .aspectRatio(2 / 3, contentMode: .fit)
                    .overlay(Imagem(url: c.item.logo, nome: c.item.name))
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                    .contentShape(RoundedRectangle(cornerRadius: 6))
            }
            .buttonStyle(Apertar())
            .modifier(MenuFavorito(item: c.item))
            Text(c.item.name).font(.system(size: 13, weight: .semibold)).foregroundColor(.white).lineLimit(1).padding(.top, 7).padding(.horizontal, 2)
            if let s = c.sub { Text(s).font(.system(size: 12)).foregroundColor(Cor.dim).lineLimit(1).padding(.top, 1).padding(.horizontal, 2) }
            BarraProgresso(fracao: c.progress).padding(.top, 6).padding(.horizontal, 2)
        }
    }
}

// ---------- Grade (categoria, busca, Minha lista) ----------

private struct GradeItens: View {
    let titulo: String
    let itens: [Item]
    let voltar: (() -> Void)?
    let vazio: (String, String)
    var carregando = false
    @Environment(\.medidas) var m
    @ObservedObject private var st = Store.s

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 8) {
                    if let v = voltar { IconeBotao(icone: Icone.voltar, acao: v) }
                    Text(titulo).font(.system(size: m.largo ? 28 : 22, weight: .bold)).foregroundColor(.white).lineLimit(1)
                    if !itens.isEmpty { Text("\(itens.count) \(itens.count == 1 ? "título" : "títulos")").font(.system(size: 14)).foregroundColor(Cor.dim) }
                }
                .padding(.bottom, 12)
                if carregando {
                    ProgressView().tint(Cor.vermelho).frame(maxWidth: .infinity).padding(40)
                } else if itens.isEmpty {
                    Mensagem(titulo: vazio.0, texto: vazio.1).padding(.top, 40)
                }
                let _ = st.versao
                LazyVGrid(columns: [GridItem(.adaptive(minimum: m.cartao), spacing: 8, alignment: .top)], spacing: 14) {
                    ForEach(itens) { i in CartaoPoster(c: cartao(i)) }
                }
            }
            .padding(.horizontal, m.margem)
            .padding(.top, 14)
            .padding(.bottom, 48)
        }
    }
}

private struct JanelaCategorias: View {
    let cats: [Category]
    let escolher: (Category?) -> Void

    var body: some View {
        Janela(largura: 460, fechar: { escolher(nil) }) {
            Text("Categorias").font(.system(size: 20, weight: .bold)).foregroundColor(.white).padding(.bottom, 8)
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(cats, id: \.name) { c in
                        Button { escolher(c) } label: {
                            HStack {
                                Text(c.name).font(.system(size: 16)).foregroundColor(.white).lineLimit(1)
                                Spacer()
                                Text("\(c.items.count)").font(.system(size: 13)).foregroundColor(Cor.dim)
                            }
                            .padding(.vertical, 11)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(Apertar())
                        Divider().background(Cor.linha)
                    }
                }
            }
            .frame(maxHeight: 480)
        }
    }
}
