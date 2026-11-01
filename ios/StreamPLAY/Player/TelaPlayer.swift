import SwiftUI
import MobileVLCKit
import UniformTypeIdentifiers

/// Onde o VLC desenha o vídeo.
private struct TelaVideo: UIViewRepresentable {
    let ctl: PlayerControle

    func makeUIView(context: Context) -> UIView {
        let v = UIView()
        v.backgroundColor = .black
        v.isUserInteractionEnabled = false
        DispatchQueue.main.async { ctl.anexar(v) }
        return v
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}

/// Player em tela cheia com controles no estilo da Netflix.
struct TelaPlayer: View {
    @StateObject private var ctl: PlayerControle
    @ObservedObject private var transmissao = Transmissao.s
    @Environment(\.scenePhase) private var fase
    @Environment(\.medidas) var m
    @State private var escolhendoLegenda = false

    init(req: PlayRequest) { _ctl = StateObject(wrappedValue: PlayerControle(req)) }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            TelaVideo(ctl: ctl).ignoresSafeArea()
            // com erro, esconde a última imagem que ficou parada na tela (às vezes de outro canal)
            if ctl.erro != nil { Color.black.ignoresSafeArea() }
            if let p = ctl.transmitindoPara { InfoTransmitindo(ctl: ctl, aparelho: p) }

            // toque: mostra/esconde os controles; dois toques nas laterais voltam/avançam 10 s
            HStack(spacing: 0) {
                areaToque(-10_000)
                areaToque(10_000)
            }
            .ignoresSafeArea()

            if ctl.ocupado && ctl.erro == nil {
                ProgressView().progressViewStyle(.circular).tint(Cor.vermelho).scaleEffect(2.2).allowsHitTesting(false)
            }
            Lampejo(flash: ctl.flash)

            if ctl.pausadoInfo { InfoPausado(ctl: ctl).transition(.opacity).onTapGesture { ctl.poke() } }

            if ctl.controles && ctl.erro == nil { Controles(ctl: ctl).transition(.opacity) }

            if let texto = ctl.retomar {
                VStack {
                    HStack(spacing: 10) {
                        Text(texto).font(.system(size: 14)).foregroundColor(.white)
                        Botao("Começar do início", tamanho: 13) { ctl.doInicio() }
                    }
                    .padding(.leading, 14).padding(.trailing, 6).padding(.vertical, 6)
                    .background(RoundedRectangle(cornerRadius: 10).fill(Cor.painel.opacity(0.95)))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(Cor.linha, lineWidth: 1))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.leading, m.margem)
                    .padding(.top, 86)
                    Spacer()
                }
            }

            if ctl.cartaoProximo, ctl.proximo != nil, ctl.erro == nil { CartaoProximo(ctl: ctl) }

            OsdView(osd: ctl.osd)

            if let e = ctl.erro {
                VStack(alignment: .leading, spacing: 0) {
                    Text(e.titulo).font(.system(size: 22, weight: .bold)).foregroundColor(.white)
                    Text(e.texto).font(.system(size: 15)).foregroundColor(Cor.sub).padding(.top, 8).padding(.bottom, 18)
                    HStack(spacing: 10) {
                        Botao("Tentar de novo", primario: true) { ctl.tentarDeNovo() }
                        if ctl.transmitindo { Botao("Assistir no celular") { ctl.transmitir(nil) } }
                        Botao("Voltar") { ctl.fechar() }
                    }
                }
                .padding(24)
                .frame(maxWidth: 520, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 12).fill(Cor.painel))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Cor.linha, lineWidth: 1))
                .padding(24)
            }

            if ctl.painel != nil {
                HStack(spacing: 0) {
                    Color.black.opacity(0.001).onTapGesture { ctl.fecharPainel() }
                    Painel(ctl: ctl, escolherLegenda: { escolhendoLegenda = true })
                        .frame(width: m.largo ? 400 : m.largura * 0.88)
                        .background(Cor.painel.opacity(0.97).ignoresSafeArea())
                        .transition(.move(edge: .trailing))
                }
            }
        }
        .animation(.easeOut(duration: 0.25), value: ctl.controles)
        .animation(.easeOut(duration: 0.25), value: ctl.painel)
        .animation(.easeOut(duration: 0.5), value: ctl.pausadoInfo)
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
            // no iPhone o vídeo fica deitado (no iPad, como estiver)
            if UIDevice.current.userInterfaceIdiom == .phone { Orientacao.travar(.landscape, .landscapeRight) }
        }
        .onDisappear {
            ctl.liberar()
            UIApplication.shared.isIdleTimerDisabled = false
            if UIDevice.current.userInterfaceIdiom == .phone { Orientacao.travar(.all, .portrait) }
        }
        .onChange(of: fase) { f in
            if f == .background { ctl.aoParar() } else if f == .active { ctl.aoVoltar() }
        }
        .fileImporter(isPresented: $escolhendoLegenda, allowedContentTypes: [.item]) { r in
            guard case .success(let u) = r else { return }
            let ok = u.startAccessingSecurityScopedResource()
            defer { if ok { u.stopAccessingSecurityScopedResource() } }
            let ext = u.pathExtension.isEmpty ? "srt" : u.pathExtension
            let destino = Pastas.cache.appendingPathComponent("legenda-\(Int(Date().timeIntervalSince1970)).\(ext)")
            if let d = try? Data(contentsOf: u), !d.isEmpty, (try? d.write(to: destino)) != nil { ctl.carregarLegenda(destino) }
            else { ctl.mostrarOsd("Não consegui abrir a legenda") }
        }
    }

    private func areaToque(_ pulo: Int64) -> some View {
        Color.black.opacity(0.001)
            .contentShape(Rectangle())
            .gesture(
                TapGesture(count: 2).onEnded { if !ctl.ehAoVivo { ctl.skip(pulo) } }
                    .exclusively(before: TapGesture().onEnded { ctl.alternarControles() })
            )
    }
}

/// Trava a tela deitada no player (e solta ao sair).
@MainActor
enum Orientacao {
    static func travar(_ mascara: UIInterfaceOrientationMask, _ girar: UIInterfaceOrientation) {
        AppDelegate.orientacoes = mascara
        if #available(iOS 16.0, *) {
            for cena in UIApplication.shared.connectedScenes {
                guard let w = cena as? UIWindowScene else { continue }
                w.windows.first?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
                w.requestGeometryUpdate(.iOS(interfaceOrientations: mascara)) { _ in }
            }
        } else {
            UIDevice.current.setValue(girar.rawValue, forKey: "orientation")
            UIViewController.attemptRotationToDeviceOrientation()
        }
    }
}

private struct Controles: View {
    @ObservedObject var ctl: PlayerControle
    @ObservedObject private var guia = Guia.s
    @Environment(\.medidas) var m

    var body: some View {
        let r = ctl.req
        VStack(spacing: 0) {
            // topo: "Voltar" e, embaixo, a capa com o nome e o episódio
            VStack(alignment: .leading, spacing: 0) {
                Botao("Voltar", icone: Icone.voltar, tamanho: 15) { ctl.fechar() }
                HStack(spacing: 14) {
                    if let l = r.item.logo, !l.isEmpty {
                        Imagem(url: l, nome: r.item.name, tamanhoNome: 7, px: 200)
                            .frame(width: m.largo ? 64 : 44, height: (m.largo ? 64 : 44) * 1.5)
                            .clipShape(RoundedRectangle(cornerRadius: 6))
                            .overlay(RoundedRectangle(cornerRadius: 6).stroke(Cor.linha, lineWidth: 1))
                    }
                    VStack(alignment: .leading, spacing: 3) {
                        Text(r.title).font(.system(size: m.largo ? 24 : 18, weight: .bold)).foregroundColor(.white).lineLimit(1)
                        let linha = r.episode.map { "T\($0.season) • E\($0.number)   \($0.title)" } ?? (r.isLive ? r.item.category : r.item.meta)
                        if !linha.isEmpty { Text(linha).font(.system(size: m.largo ? 15 : 13)).foregroundColor(Color(hex: 0xE4E4EA)).lineLimit(1) }
                    }
                }
                .padding(.top, 14)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, m.margem)
            .padding(.top, 12)
            .padding(.bottom, 28)
            .background(LinearGradient(stops: [.init(color: Cor.fundo.opacity(0.94), location: 0), .init(color: Cor.fundo.opacity(0.7), location: 0.55),
                                               .init(color: .clear, location: 1)], startPoint: .top, endPoint: .bottom).ignoresSafeArea())

            Spacer(minLength: 0)

            // rodapé: barra de tempo e botões
            VStack(alignment: .leading, spacing: 0) {
                if ctl.ehAoVivo {
                    HStack(spacing: 0) {
                        PontoAoVivo()
                        Text("  AO VIVO").font(.system(size: 14, weight: .bold)).foregroundColor(.white)
                        if !r.item.category.isEmpty { Text("  •  \(r.item.category)").font(.system(size: 14)).foregroundColor(Cor.sub) }
                    }
                    .padding(.bottom, 8)
                    // guia de programação do canal
                    if guia.versao >= 0 && guia.temAlgum {
                        let ag = guia.agoraESeguir(r.item, agoraMs())
                        if let a = ag.0 {
                            Text("Agora: \(a.titulo)  ·  \(a.horario)").font(.system(size: m.largo ? 16 : 14, weight: .semibold)).foregroundColor(.white).lineLimit(1)
                        }
                        if let s = ag.1 {
                            Text("A seguir: \(s.titulo)  ·  \(Guia.hhmm(s.inicio))").font(.system(size: m.largo ? 14 : 13)).foregroundColor(Cor.sub).lineLimit(1).padding(.top, 2)
                        }
                        if ag.0 != nil || ag.1 != nil { Spacer().frame(height: 8) }
                    }
                } else {
                    HStack(spacing: 14) {
                        BarraTempo(ctl: ctl)
                        let resta = ctl.arrastando ? Int64((1 - ctl.posArrasto) * Double(ctl.duracao)) : ctl.duracao - ctl.tempo
                        Text(ctl.duracao > 0 ? tempo(resta) : "").font(.system(size: 14).monospacedDigit()).foregroundColor(.white)
                    }
                }
                HStack(spacing: 2) {
                    BotaoPlayer(icone: ctl.tocando ? Icone.pausa : Icone.play, texto: ctl.tocando ? "Pausar" : "Reproduzir") { ctl.togglePlay() }
                    if !ctl.ehAoVivo {
                        BotaoPlayer(icone: Icone.voltar10, texto: "Voltar 10 segundos") { ctl.skip(-10_000) }
                        BotaoPlayer(icone: Icone.avancar10, texto: "Avançar 10 segundos") { ctl.skip(10_000) }
                    }
                    if ctl.variosCanais {
                        BotaoPlayer(icone: Icone.acima, texto: "Canal anterior") { ctl.zap(-1) }
                        BotaoPlayer(icone: Icone.abaixo, texto: "Próximo canal") { ctl.zap(1) }
                    }
                    Spacer(minLength: 0)
                    let textos = m.largura >= 900
                    if ctl.proximo != nil { BotaoPlayer(icone: Icone.proximo, texto: "Próximo episódio", comTexto: textos) { ctl.playNext() } }
                    if ctl.temEpisodios { BotaoPlayer(icone: Icone.episodios, texto: "Episódios", comTexto: textos) { ctl.abrirPainel("episodios") } }
                    if ctl.variosCanais { BotaoPlayer(icone: Icone.canais, texto: "Canais", comTexto: textos) { ctl.abrirPainel("canais") } }
                    BotaoPlayer(icone: Icone.transmitir, texto: ctl.transmitindo ? "Transmitindo" : "Transmitir para a TV", ativo: ctl.transmitindo) { ctl.abrirTransmitir() }
                    BotaoPlayer(icone: Icone.legendas, texto: "Áudio e legendas", comTexto: textos) { ctl.abrirPainel("legendas") }
                    if !ctl.ehAoVivo && !ctl.transmitindo { BotaoPlayer(icone: Icone.velocidade, texto: "Velocidade", comTexto: textos) { ctl.abrirPainel("velocidade") } }
                }
                .padding(.top, 6)
            }
            .padding(.horizontal, m.margem)
            .padding(.top, 40)
            .padding(.bottom, 12)
            .background(LinearGradient(colors: [.clear, Cor.fundo.opacity(0.92)], startPoint: .top, endPoint: .bottom).ignoresSafeArea())
        }
    }
}

private struct PontoAoVivo: View {
    @State private var aceso = true

    var body: some View {
        Circle().fill(Cor.vermelho).frame(width: 10, height: 10).opacity(aceso ? 1 : 0.25)
            .onAppear { withAnimation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true)) { aceso = false } }
    }
}

private struct BotaoPlayer: View {
    let icone: String
    let texto: String
    var comTexto = false
    var ativo = false
    let acao: () -> Void

    var body: some View {
        Button(action: acao) {
            HStack(spacing: 6) {
                Image(systemName: icone).font(.system(size: 22, weight: .semibold)).foregroundColor(ativo ? Cor.vermelho : .white)
                if comTexto { Text(texto).font(.system(size: 14, weight: .semibold)).foregroundColor(.white) }
            }
            .padding(.horizontal, comTexto ? 12 : 9)
            .padding(.vertical, 9)
            .contentShape(Rectangle())
        }
        .buttonStyle(Apertar())
        .accessibilityLabel(texto)
    }
}

private struct BarraTempo: View {
    @ObservedObject var ctl: PlayerControle

    var body: some View {
        GeometryReader { g in
            let w = g.size.width
            let frac = ctl.arrastando ? ctl.posArrasto : ctl.duracao > 0 ? min(1, max(0, Double(ctl.tempo) / Double(ctl.duracao))) : 0
            let alt: CGFloat = ctl.arrastando ? 6 : 4
            let bola: CGFloat = ctl.arrastando ? 18 : 13
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.3)).frame(height: alt)
                Capsule().fill(Cor.vermelho).frame(width: w * frac, height: alt)
                Circle().fill(Cor.vermelho).overlay(Circle().stroke(Color.white, lineWidth: 2)).frame(width: bola, height: bola).offset(x: w * frac - bola / 2)
                if ctl.arrastando && ctl.duracao > 0 {
                    Text(tempo(Int64(ctl.posArrasto * Double(ctl.duracao)))).font(.system(size: 13, weight: .semibold).monospacedDigit()).foregroundColor(.white)
                        .padding(.horizontal, 7).padding(.vertical, 2)
                        .background(RoundedRectangle(cornerRadius: 6).fill(Cor.painel))
                        .offset(x: min(max(0, w * frac - 28), max(0, w - 56)), y: -26)
                }
            }
            .frame(height: 36)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { v in ctl.arrastar(Double(v.location.x / max(1, w))) }
                    .onEnded { _ in ctl.soltar() }
            )
        }
        .frame(height: 36)
    }
}

private struct Lampejo: View {
    let flash: Flash?
    @State private var alfa: Double = 0

    var body: some View {
        Group {
            if let f = flash {
                if f.tipo == "rew" || f.tipo == "fwd" {
                    HStack {
                        if f.tipo == "fwd" { Spacer() }
                        VStack(spacing: 4) {
                            Image(systemName: f.tipo == "rew" ? Icone.voltar10 : Icone.avancar10).font(.system(size: 48)).foregroundColor(.white)
                            Text("10 s").font(.system(size: 15, weight: .bold)).foregroundColor(.white)
                        }
                        .padding(.horizontal, 80)
                        if f.tipo == "rew" { Spacer() }
                    }
                } else {
                    Image(systemName: f.tipo == "pause" ? Icone.pausa : Icone.play).font(.system(size: 40)).foregroundColor(.white)
                        .frame(width: 84, height: 84)
                        .background(Circle().fill(Color.black.opacity(0.6)))
                        .scaleEffect(0.85 + 0.4 * (1 - alfa))
                }
            }
        }
        .opacity(alfa)
        .allowsHitTesting(false)
        .onChange(of: flash) { _ in
            alfa = 1
            withAnimation(.easeOut(duration: 0.65)) { alfa = 0 }
        }
    }
}

private struct OsdView: View {
    let osd: Osd?
    @State private var visivel: String?

    var body: some View {
        VStack {
            if let t = visivel {
                Text(t).font(.system(size: 22, weight: .bold)).foregroundColor(.white)
                    .padding(.horizontal, 18).padding(.vertical, 8)
                    .background(RoundedRectangle(cornerRadius: 10).fill(Cor.painel.opacity(0.9)))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(Cor.linha, lineWidth: 1))
                    .padding(.top, 120)
                    .transition(.opacity)
            }
            Spacer()
        }
        .allowsHitTesting(false)
        .animation(.easeOut(duration: 0.3), value: visivel)
        .task(id: osd?.n) {
            guard let o = osd else { return }
            visivel = o.texto
            try? await Task.sleep(nanoseconds: 1_700_000_000)
            if !Task.isCancelled { visivel = nil }
        }
    }
}

private struct InfoPausado: View {
    @ObservedObject var ctl: PlayerControle
    @Environment(\.medidas) var m

    var body: some View {
        let r = ctl.req
        ZStack(alignment: .bottomTrailing) {
            LinearGradient(colors: [Cor.fundo.opacity(0.92), .clear], startPoint: .leading, endPoint: .trailing).ignoresSafeArea()
            VStack(alignment: .leading, spacing: 0) {
                Spacer()
                Text("Você está assistindo").font(.system(size: 15)).foregroundColor(Cor.sub)
                Text(r.title).font(.system(size: 36, weight: .black)).foregroundColor(.white).padding(.vertical, 6)
                if let ep = r.episode { Text("\(ep.label)  \"\(ep.title)\"").font(.system(size: 18, weight: .semibold)).foregroundColor(.white) }
                let programa = r.isLive ? Guia.s.agoraESeguir(r.item, agoraMs()).0 : nil
                if let p = programa { Text("Agora: \(p.titulo)  ·  \(p.horario)").font(.system(size: 18, weight: .semibold)).foregroundColor(.white) }
                if let plot = r.episode?.plot ?? r.item.plot ?? programa?.descricao, !plot.isEmpty {
                    Text(plot).font(.system(size: 15)).foregroundColor(Cor.sub).lineLimit(5).padding(.top, 12)
                }
                Spacer()
            }
            .frame(maxWidth: 560, alignment: .leading)
            .padding(.leading, m.margem * 2)
            .frame(maxWidth: .infinity, alignment: .leading)
            Text("Pausado").font(.system(size: 15)).foregroundColor(Cor.sub).padding(m.margem)
        }
    }
}

private struct CartaoProximo: View {
    @ObservedObject var ctl: PlayerControle
    @Environment(\.medidas) var m

    var body: some View {
        if let p = ctl.proximo {
            VStack {
                Spacer()
                HStack {
                    Spacer()
                    VStack(alignment: .leading, spacing: 0) {
                        Text("Próximo episódio").font(.system(size: 13)).foregroundColor(Cor.sub)
                        Text("\(p.label)  \(p.title)").font(.system(size: 16, weight: .semibold)).foregroundColor(.white).lineLimit(2).padding(.top, 2).padding(.bottom, 12)
                        HStack(spacing: 10) {
                            Button { ctl.playNext() } label: {
                                HStack(spacing: 6) {
                                    Image(systemName: Icone.play).font(.system(size: 16))
                                    Text("Assistir").font(.system(size: 15, weight: .semibold))
                                }
                                .foregroundColor(.white)
                                .padding(.horizontal, 16).padding(.vertical, 9)
                                .background(
                                    GeometryReader { g in
                                        ZStack(alignment: .leading) {
                                            Cor.vermelho
                                            if Store.s.autoNext { Color.black.opacity(0.3).frame(width: g.size.width * ctl.fracProximo) }
                                        }
                                    }
                                )
                                .clipShape(RoundedRectangle(cornerRadius: 6))
                            }
                            .buttonStyle(Apertar())
                            Botao("Ver créditos", tamanho: 15) { ctl.dispensarCreditos() }
                        }
                    }
                    .padding(18)
                    .frame(maxWidth: 380, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Cor.painel))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Cor.linha, lineWidth: 1))
                }
                .padding(.trailing, m.margem)
                .padding(.bottom, ctl.controles ? 130 : 40)
            }
        }
    }
}

/// Transmitindo: o vídeo está na TV; aqui fica o aviso (e os controles por cima).
private struct InfoTransmitindo: View {
    @ObservedObject var ctl: PlayerControle
    let aparelho: String

    var body: some View {
        let r = ctl.req
        ZStack {
            Cor.fundo.ignoresSafeArea()
            VStack(spacing: 6) {
                Image(systemName: Icone.transmitir).font(.system(size: 56)).foregroundColor(Cor.vermelho)
                Text("Transmitindo para \(aparelho)").font(.system(size: 17)).foregroundColor(Cor.sub).padding(.top, 10)
                Text(r.title).font(.system(size: 28, weight: .bold)).foregroundColor(.white).multilineTextAlignment(.center)
                if let ep = r.episode { Text("T\(ep.season) • E\(ep.number)   \(ep.title)").font(.system(size: 15)).foregroundColor(Color(hex: 0xD0D0D0)) }
            }
            .padding(.horizontal, 32)
            .frame(maxWidth: 640)
        }
    }
}

// ---------- Painéis (áudio e legendas, velocidade, episódios, canais, transmitir) ----------

private struct Painel: View {
    @ObservedObject var ctl: PlayerControle
    let escolherLegenda: () -> Void

    var body: some View {
        ScrollViewReader { rolar in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    switch ctl.painel ?? "" {
                    case "legendas": PainelLegendas(ctl: ctl, escolherLegenda: escolherLegenda)
                    case "velocidade": PainelVelocidade(ctl: ctl)
                    case "episodios": PainelEpisodios(ctl: ctl)
                    case "canais": PainelCanais(ctl: ctl)
                    case "transmitir": PainelTransmitir(ctl: ctl)
                    default: EmptyView()
                    }
                }
                .padding(.bottom, 24)
            }
            .task {
                // episódios e canais: abre já no atual
                try? await Task.sleep(nanoseconds: 150_000_000)
                withAnimation { rolar.scrollTo("atual", anchor: .center) }
            }
        }
    }
}

private func titulo(_ t: String) -> some View {
    Text(t).font(.system(size: 20, weight: .bold)).foregroundColor(.white).padding(.leading, 20).padding(.top, 22).padding(.bottom, 10)
}

private struct Opcao<Inicio: View>: View {
    let texto: String
    let selecionado: Bool
    let inicio: Inicio
    let acao: () -> Void

    init(_ texto: String, _ selecionado: Bool, acao: @escaping () -> Void) where Inicio == EmptyView {
        self.texto = texto
        self.selecionado = selecionado
        self.inicio = EmptyView()
        self.acao = acao
    }

    init(_ texto: String, _ selecionado: Bool, @ViewBuilder inicio: () -> Inicio, acao: @escaping () -> Void) {
        self.texto = texto
        self.selecionado = selecionado
        self.inicio = inicio()
        self.acao = acao
    }

    var body: some View {
        Button(action: acao) {
            HStack(spacing: 12) {
                ZStack { if selecionado { Image(systemName: Icone.check).font(.system(size: 16, weight: .semibold)).foregroundColor(.white) } }.frame(width: 22)
                inicio
                Text(texto).font(.system(size: 16, weight: selecionado ? .semibold : .regular)).foregroundColor(selecionado ? .white : Cor.sub).lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            .background(selecionado ? Color.white.opacity(0.2) : .clear)
            .contentShape(Rectangle())
        }
        .buttonStyle(Apertar())
    }
}

private struct PainelTransmitir: View {
    @ObservedObject var ctl: PlayerControle
    @ObservedObject private var t = Transmissao.s

    var body: some View {
        titulo("Transmitir para a TV")
        Opcao("Este aparelho", !ctl.transmitindo) { ctl.transmitir(nil) }
        ForEach(Array(t.aparelhos.enumerated()), id: \.offset) { _, a in
            Opcao(Transmissao.nome(a), ctl.transmitindo && t.ehOAtual(a)) { ctl.transmitir(a) }
        }
        if t.aparelhos.isEmpty {
            HStack(spacing: 10) {
                ProgressView().tint(Cor.sub)
                Text("Procurando aparelhos na rede...").font(.system(size: 15)).foregroundColor(Cor.dim)
            }
            .padding(.leading, 50).padding(.top, 8)
        }
        Text("Funciona com Chromecast e TVs com Chromecast embutido. A TV e este aparelho precisam estar no mesmo Wi-Fi, e o StreamPLAY fica aberto enquanto a TV mostra o vídeo. Se o iPhone perguntar, permita o acesso à rede local.")
            .font(.system(size: 13)).foregroundColor(Cor.dim).padding(.horizontal, 20).padding(.top, 16)
    }
}

private struct PainelLegendas: View {
    @ObservedObject var ctl: PlayerControle
    let escolherLegenda: () -> Void

    var body: some View {
        let _ = ctl.faixas
        let audios = ctl.faixasAudio()
        let legendas = ctl.faixasLegenda()
        let aAtual = ctl.audioAtual()
        let lAtual = ctl.legendaAtual()
        titulo("Áudio")
        if audios.isEmpty { Text("Padrão").font(.system(size: 16)).foregroundColor(Cor.dim).padding(.leading, 50).padding(.bottom, 6) }
        ForEach(audios, id: \.0) { a in Opcao(a.1, a.0 == aAtual) { ctl.escolherAudio(a.0) } }
        titulo("Legendas")
        Opcao("Desativadas", lAtual == -1) { ctl.escolherLegenda(-1) }
        ForEach(legendas, id: \.0) { l in Opcao(l.1, l.0 == lAtual) { ctl.escolherLegenda(l.0) } }
        Opcao("+ Carregar arquivo de legenda...", false, acao: escolherLegenda)
    }
}

private struct PainelVelocidade: View {
    @ObservedObject var ctl: PlayerControle

    var body: some View {
        titulo("Velocidade")
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 8, alignment: .leading)], alignment: .leading, spacing: 8) {
            ForEach([Float(0.5), 0.75, 1, 1.25, 1.5], id: \.self) { r in
                Chip(texto: r == 1 ? "1x (Normal)" : fmtVelocidade(r), selecionado: ctl.velocidade == r) { ctl.definirVelocidade(r) }
            }
        }
        .padding(.horizontal, 20)
        titulo("Imagem")
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), spacing: 8, alignment: .leading)], alignment: .leading, spacing: 8) {
            ForEach([("original", "Original"), ("fill", "Preencher a tela"), ("16:9", "16:9"), ("4:3", "4:3")], id: \.0) { a in
                Chip(texto: a.1, selecionado: ctl.aspecto == a.0) { ctl.definirAspecto(a.0) }
            }
        }
        .padding(.horizontal, 20)
    }
}

private struct PainelEpisodios: View {
    @ObservedObject var ctl: PlayerControle
    @State private var temporada = 0

    var body: some View {
        let r = ctl.req
        let show = r.item
        if let atual = r.episode {
            let t = temporada == 0 ? atual.season : temporada
            titulo(show.name)
            if show.seasons.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) { ForEach(show.seasons, id: \.self) { s in Chip(texto: "Temporada \(s)", selecionado: s == t) { temporada = s } } }
                        .padding(.horizontal, 16).padding(.vertical, 4)
                }
            }
            ForEach(show.episodes.filter { $0.season == t }) { ep in
                let p = Store.s.getProgress(ep.key)
                Opcao("\(ep.number). \(ep.title)", ep == atual, inicio: {
                    if let p = p { BarraProgresso(fracao: p.finished ? 1 : p.fraction).frame(width: 36) }
                }) {
                    if ep != atual { if let n = PlayRequest.forEpisode(ep) { ctl.trocar(n) } } else { ctl.fecharPainel() }
                }
                .id(ep == atual ? "atual" : ep.key)
            }
        }
    }
}

private struct PainelCanais: View {
    @ObservedObject var ctl: PlayerControle

    var body: some View {
        let r = ctl.req
        if let lista = r.channels {
            titulo("Canais")
            LazyVStack(spacing: 0) {
                ForEach(lista) { ch in
                    Opcao(ch.name, ch == r.item, inicio: {
                        Imagem(url: ch.logo, nome: ch.name, crop: false, tamanhoNome: 7, px: 120).frame(width: 52, height: 30).clipShape(RoundedRectangle(cornerRadius: 3))
                    }) {
                        if ch != r.item { ctl.trocar(PlayRequest.forChannel(ch, lista)) } else { ctl.fecharPainel() }
                    }
                    .id(ch == r.item ? "atual" : ch.key)
                }
            }
        }
    }
}
