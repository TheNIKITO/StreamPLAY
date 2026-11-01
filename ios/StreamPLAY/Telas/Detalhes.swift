import SwiftUI

/// Janela "Mais informações" (filme ou série com temporadas e episódios).
struct TelaDetalhes: View {
    let item: Item
    @Environment(\.medidas) var m
    @ObservedObject private var e = Estado.s
    @ObservedObject private var st = Store.s

    var body: some View {
        let _ = st.versao
        // o que o botão principal faz (igual ao PC e ao Android)
        let atualEp: Episode? = item.kind == .movie ? nil : Resume.episodeFor(item)
        let progresso: Progresso? = {
            let p = item.kind == .movie ? st.getProgress(item.key) : atualEp.flatMap { st.getProgress($0.key) }
            return p.flatMap { !$0.finished && $0.fraction >= 0.01 ? $0 : nil }
        }()
        let textoTocar: String = {
            if item.kind == .movie { return progresso != nil ? "Continuar" : "Assistir" }
            guard let ep = atualEp else { return "Assistir" }
            return (progresso != nil || ep != item.episodes.first ? "Continuar " : "Assistir ") + ep.label
        }()
        // temporada escolhida nos chips; antes de escolher, a do episódio atual
        let temporada = e.temporada != 0 ? e.temporada : atualEp?.season ?? 1
        let fav = st.isFav(item.key)
        let cheia = !m.largo

        ZStack(alignment: .top) {
            Color.black.opacity(0.7).ignoresSafeArea().onTapGesture { e.fecharDetalhes() }
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    let alt = min(max((min(m.largura, 900) * (cheia ? 1 : 0.92)) * 0.56, 260), 460)
                    ZStack(alignment: .bottomLeading) {
                        FundoDestaque(item: item, altura: alt)
                        LinearGradient(stops: [.init(color: .clear, location: 0.35), .init(color: Cor.janela, location: 1)], startPoint: .top, endPoint: .bottom)
                        VStack(alignment: .leading, spacing: 0) {
                            RotuloTipo(texto: item.kindLabel)
                            Text(item.name).font(.system(size: m.largo ? 34 : 26, weight: .black)).foregroundColor(.white).lineLimit(3)
                                .padding(.top, 4).padding(.bottom, 14)
                            HStack(spacing: 10) {
                                Botao(textoTocar, icone: Icone.play, primario: true) {
                                    if item.kind == .movie { e.tocar(PlayRequest.forItem(item)) } else if let ep = atualEp { e.tocar(PlayRequest.forEpisode(ep)) }
                                }
                                if progresso != nil {
                                    IconeBotao(icone: Icone.recomecar, fundo: Color.black.opacity(0.4), contorno: true) {
                                        let req = item.kind == .movie ? PlayRequest.forItem(item) : PlayRequest.forEpisode(atualEp)
                                        if let r = req { r.fromStart = true; e.tocar(r) }
                                    }
                                }
                                IconeBotao(icone: fav ? Icone.check : Icone.mais, fundo: Color.black.opacity(0.4), contorno: true) { e.favoritar(item) }
                            }
                        }
                        .padding(.horizontal, 24)
                        .padding(.vertical, 14)
                    }
                    .frame(height: alt)
                    .overlay(alignment: .topTrailing) {
                        IconeBotao(icone: Icone.fechar, fundo: Cor.janela.opacity(0.8)) { e.fecharDetalhes() }.padding(14)
                    }

                    VStack(alignment: .leading, spacing: 0) {
                        if let p = progresso {
                            HStack(spacing: 12) {
                                BarraProgresso(fracao: p.fraction).frame(width: 200)
                                Text(faltam(p.dur - p.pos)).font(.system(size: 13)).foregroundColor(Cor.sub)
                            }
                            .padding(.bottom, 12)
                        }
                        HStack(spacing: 12) {
                            if item.isRelease { Selo(texto: "LANÇAMENTO") }
                            if let y = item.year, !y.isEmpty { meta(y, Color(hex: 0xBCBCBC)) }
                            if let r = item.rating, !r.isEmpty { meta("★ " + r, Cor.verde) }
                            if item.kind == .series { meta(item.countText, Color(hex: 0xBCBCBC)) }
                            Text("HD").font(.system(size: 11, weight: .semibold)).foregroundColor(.white).padding(.horizontal, 6).padding(.vertical, 1)
                                .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(hex: 0x808080), lineWidth: 1))
                        }
                        let plot = item.plot ?? (item.kind == .series ? "Escolha a temporada e o episódio abaixo." : nil)
                        if let p = plot, !p.trimmingCharacters(in: .whitespaces).isEmpty {
                            Text(p).font(.system(size: 15)).foregroundColor(.white).lineSpacing(4).padding(.top, 12)
                        }
                        if let g = item.genre, !g.isEmpty { rotulado("Gênero: ", g) }
                        if !item.categories.isEmpty { rotulado(item.categories.count > 1 ? "Categorias: " : "Categoria: ", item.categories.joined(separator: ", ")) }
                    }
                    .padding(.horizontal, 24)

                    if item.kind == .series {
                        let seasons = item.seasons
                        HStack(alignment: .lastTextBaseline) {
                            Text("Episódios").font(.system(size: 22, weight: .bold)).foregroundColor(.white)
                            if seasons.count <= 1 { Text("   Temporada \(seasons.first ?? 1)").font(.system(size: 15)).foregroundColor(Cor.sub) }
                        }
                        .padding(.horizontal, 24).padding(.top, 26).padding(.bottom, 8)
                        if seasons.count > 1 {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 8) {
                                    ForEach(seasons, id: \.self) { s in Chip(texto: "Temporada \(s)", selecionado: s == temporada) { e.temporada = s } }
                                }
                                .padding(.horizontal, 24).padding(.vertical, 6)
                            }
                        }
                        LazyVStack(spacing: 0) {
                            ForEach(item.episodes.filter { $0.season == temporada }) { ep in LinhaEpisodio(ep: ep, atual: ep == atualEp) }
                        }
                    }
                }
                .padding(.bottom, 40)
                .frame(maxWidth: cheia ? .infinity : 900)
                .background(Cor.janela)
                .clipShape(RoundedRectangle(cornerRadius: cheia ? 0 : 8))
                .frame(maxWidth: .infinity)
                .padding(.top, cheia ? 0 : 24)
                .padding(.horizontal, cheia ? 0 : m.largura * 0.04)
            }
        }
    }

    private func meta(_ t: String, _ c: Color) -> some View {
        Text(t).font(.system(size: 15, weight: .semibold)).foregroundColor(c)
    }

    private func rotulado(_ r: String, _ v: String) -> some View {
        (Text(r).foregroundColor(Cor.dim) + Text(v).foregroundColor(.white)).font(.system(size: 13)).padding(.top, 8)
    }
}

private struct LinhaEpisodio: View {
    let ep: Episode
    let atual: Bool
    @Environment(\.medidas) var m

    var body: some View {
        let p = Store.s.getProgress(ep.key)
        Button { Estado.s.tocar(PlayRequest.forEpisode(ep)) } label: {
            HStack(spacing: 0) {
                Text("\(ep.number)").font(.system(size: 22)).foregroundColor(Color(hex: 0xD2D2D2)).frame(width: 40, alignment: .leading)
                ZStack(alignment: .bottom) {
                    // imagem própria do episódio (TMDB); se não abrir, fica a capa da série
                    if ep.image != ep.show.logo && !(ep.show.logo ?? "").isEmpty { Imagem(url: ep.show.logo, nome: ep.show.name, tamanhoNome: 10, px: 320) }
                    Imagem(url: ep.image, nome: ep.show.name, tamanhoNome: 10, px: 320, semFundo: ep.image != ep.show.logo)
                    if let p = p, !p.finished, p.fraction > 0.01 { BarraProgresso(fracao: p.fraction) }
                }
                .frame(width: m.largo ? 150 : 112, height: (m.largo ? 150 : 112) * 9 / 16)
                .clipShape(RoundedRectangle(cornerRadius: 4))
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(ep.title).font(.system(size: 16, weight: .semibold)).foregroundColor(.white).lineLimit(1)
                        Spacer(minLength: 8)
                        if let p = p, p.dur > 0 { Text(p.finished ? "Assistido" : tempo(p.dur)).font(.system(size: 13)).foregroundColor(Cor.sub) }
                    }
                    if let pl = ep.plot, !pl.isEmpty {
                        Text(pl).font(.system(size: 13)).foregroundColor(Cor.sub).lineLimit(3).multilineTextAlignment(.leading)
                    }
                }
                .padding(.leading, 16)
            }
            .padding(.vertical, 12)
            .padding(.horizontal, 12)
            .background(RoundedRectangle(cornerRadius: 6).fill(atual ? Color(hex: 0x2E2E2E) : .clear))
            .contentShape(Rectangle())
        }
        .buttonStyle(Apertar())
        .padding(.horizontal, 12)
    }
}
