import SwiftUI

struct Raiz: View {
    @ObservedObject var e = Estado.s

    var body: some View {
        GeometryReader { g in
            ZStack {
                Cor.fundo.ignoresSafeArea()
                switch e.tela {
                case .login(let aviso): TelaLogin(aviso: aviso)
                case .bloqueio(let n): if let r = e.bloqueio { TelaBloqueio(r: r).id(n) }
                case .fonte(let erro, let n): TelaFonte(erro: erro).id(n)
                case .principal: TelaPrincipal()
                case .abertura: EmptyView()
                }

                if e.player == nil {
                    if let d = e.detalhes { TelaDetalhes(item: d).id(d.key).transition(.opacity) }
                    if e.ajustes { TelaAjustes().transition(.opacity) }
                }

                if e.janela == "avisos" { JanelaAvisos { e.janela = nil } }
                if e.janela == "conta" { JanelaConta { e.janela = nil } }

                BannerAtualizacao()
                Abertura()
                Avisos()
                if !e.novidades.isEmpty && e.player == nil { JanelaNovidades(lista: e.novidades) { e.novidades = [] } }

                if let p = e.player { TelaPlayer(req: p).id(p.id).transition(.opacity) }
            }
            .environment(\.medidas, Medidas(largura: g.size.width, altura: g.size.height))
            .animation(.easeOut(duration: 0.2), value: e.detalhes?.key)
            .animation(.easeOut(duration: 0.2), value: e.ajustes)
            .animation(.easeOut(duration: 0.38), value: e.abertura)
            .animation(.easeOut(duration: 0.15), value: e.janela)
        }
        .preferredColorScheme(.dark)
        .statusBarHidden(e.player != nil)
        .onAppear { e.naFrente(true) }
    }
}

struct TelaPrincipal: View {
    @ObservedObject var e = Estado.s

    var body: some View {
        if let lib = e.lib {
            VStack(spacing: 0) {
                BarraTopo()
                ZStack {
                    switch e.tab {
                    case "channels": TelaCanais(lib: lib)
                    case "movies": TelaNavegar(lib: lib, kind: .movie).id("movies")
                    case "series": TelaNavegar(lib: lib, kind: .series).id("series")
                    case "mylist": TelaNavegar(lib: lib, kind: nil).id("mylist")
                    default: TelaNavegar(lib: lib, kind: nil, inicio: true).id("home")
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }
}

struct BarraTopo: View {
    @ObservedObject var e = Estado.s
    @Environment(\.medidas) var m
    @State private var buscando = false
    @FocusState private var campo: Bool

    var body: some View {
        VStack(spacing: 0) {
            VStack(spacing: 4) {
                HStack(spacing: 4) {
                    if !(buscando && !m.largo) { Logo(altura: m.largo ? 26 : 21) }
                    if m.largo { Abas().padding(.leading, 24) }
                    Spacer(minLength: 8)
                    if buscando {
                        CampoBusca(campo: $campo) { buscando = false; campo = false; e.limparBusca() }
                            .frame(maxWidth: m.largo ? 260 : .infinity)
                            .task { try? await Task.sleep(nanoseconds: 100_000_000); campo = true }
                    } else {
                        IconeBotao(icone: Icone.busca) { buscando = true }
                    }
                    ZStack(alignment: .topTrailing) {
                        IconeBotao(icone: Icone.sino) { e.janela = "avisos"; e.avisosNovos = false }
                        if e.avisosNovos {
                            Circle().fill(Cor.vermelho).frame(width: 9, height: 9)
                                .overlay(Circle().stroke(Cor.fundo, lineWidth: 1.5)).padding(.top, 8).padding(.trailing, 9)
                        }
                    }
                    IconeBotao(icone: Icone.usuario) { e.janela = "conta" }
                }
                .frame(height: 52)
                if !m.largo { Abas() }
            }
            .padding(.horizontal, m.margem)
            .padding(.top, 4)
            .padding(.bottom, 5)
            LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: Cor.vermelho.opacity(0.65), location: 0.25),
                                   .init(color: Cor.vermelho.opacity(0.65), location: 0.75), .init(color: .clear, location: 1)],
                           startPoint: .leading, endPoint: .trailing)
                .frame(height: 1)
        }
        .background(Cor.fundo.ignoresSafeArea(edges: .top))
        .onChange(of: e.busca) { b in if !b.isEmpty { buscando = true } }
    }
}

private struct Abas: View {
    @ObservedObject var e = Estado.s
    private let abas: [(String, String, String)] = [
        ("home", "Início", Icone.inicio), ("channels", "Canais", Icone.aoVivo), ("movies", "Filmes", Icone.filme),
        ("series", "Séries", Icone.serie), ("mylist", "Minha lista", Icone.marcador),
    ]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 2) {
                ForEach(abas, id: \.0) { a in
                    let sel = e.tab == a.0
                    Button { e.navegar(a.0) } label: {
                        HStack(spacing: 7) {
                            Image(systemName: a.2).font(.system(size: 16))
                            Text(a.1).font(.system(size: 15, weight: sel ? .semibold : .regular)).lineLimit(1)
                        }
                        .foregroundColor(sel ? Cor.vermelhoClaro : Cor.texto)
                        .padding(.horizontal, 11)
                        .padding(.vertical, 8)
                        // a aba aberta ganha o traço vermelho embaixo
                        .overlay(Rectangle().fill(sel ? Cor.vermelho : .clear).frame(height: 3), alignment: .bottom)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(Apertar())
                }
            }
        }
    }
}

private struct CampoBusca: View {
    @ObservedObject var e = Estado.s
    var campo: FocusState<Bool>.Binding
    let fechar: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: Icone.busca).foregroundColor(.white)
            ZStack(alignment: .leading) {
                if e.busca.isEmpty { Text("Títulos, canais...").foregroundColor(Cor.dim).font(.system(size: 15)) }
                TextField("", text: $e.busca)
                    .focused(campo)
                    .foregroundColor(.white)
                    .font(.system(size: 15))
                    .submitLabel(.search)
                    .disableAutocorrection(true)
                    .onSubmit { campo.wrappedValue = false }
            }
            IconeBotao(icone: Icone.fechar, tamanho: 34, acao: fechar)
        }
        .padding(.leading, 10)
        .frame(height: 40)
        .background(RoundedRectangle(cornerRadius: 4).fill(Color.black.opacity(0.8)))
        .overlay(RoundedRectangle(cornerRadius: 4).stroke(campo.wrappedValue ? Color.white : Color(hex: 0x808080), lineWidth: 1))
    }
}

/// Janela do sino: avisos guardados (vencimento, atualização, lista atualizada).
struct JanelaAvisos: View {
    @ObservedObject var e = Estado.s
    let fechar: () -> Void

    var body: some View {
        Janela(fechar: fechar) {
            Text("Avisos").font(.system(size: 19, weight: .bold)).foregroundColor(.white)
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if e.avisos.isEmpty { Text("Nenhum aviso por enquanto.").font(.system(size: 15)).foregroundColor(Cor.sub) }
                    ForEach(Array(e.avisos.enumerated()), id: \.offset) { _, a in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(a.0).font(.system(size: 15)).foregroundColor(Color(hex: 0xEDEDF2))
                            Text(a.1).font(.system(size: 12)).foregroundColor(Cor.dim)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 10)
            }
            .frame(maxHeight: 420)
            Botao("Fechar", acao: fechar).padding(.top, 18)
        }
    }
}

/// Janela do perfil: nome, login, vencimento, Configurações e Sair da conta.
struct JanelaConta: View {
    @ObservedObject var e = Estado.s
    let fechar: () -> Void
    @State private var sair = false

    var body: some View {
        let a = e.contaAtual?.acesso
        Janela(fechar: fechar) {
            Text((a?.nome ?? "").isEmpty ? "Minha conta" : a!.nome!).font(.system(size: 19, weight: .bold)).foregroundColor(.white)
            if let l = Store.s.login, !l.isEmpty { Text("Login: \(l)").font(.system(size: 14)).foregroundColor(Cor.sub).padding(.top, 3) }
            Text(vencimento(a)).font(.system(size: 15)).foregroundColor(Color(hex: 0xD8D8E0)).padding(.top, 12)
            HStack(spacing: 10) {
                Botao("Configurações") { fechar(); e.abrirAjustes() }
                Botao("Sair da conta") { sair = true }
            }
            .padding(.top, 18)
        }
        .alert("Sair da sua conta? Para usar o StreamPLAY de novo, será preciso digitar o login e a senha.", isPresented: $sair) {
            Button("Cancelar", role: .cancel) {}
            Button("Sair", role: .destructive) { fechar(); e.sairDaConta(nil) }
        }
    }

    private func vencimento(_ a: Conta.Acesso?) -> String {
        if let v = a?.faturaVenc { return "Próximo vencimento: \(Dia.fmt(v))" + (a?.faturaValor.map { "  •  \(Conta.dinheiro($0))" } ?? "") }
        if let l = a?.liberadoAte { return "Acesso liberado até \(Dia.fmt(l))" }
        return "Acesso liberado"
    }
}

/// Janela das novidades do PLAYGER (aparece uma vez ao abrir o app).
private struct JanelaNovidades: View {
    let lista: [Novidades.Novidade]
    let fechar: () -> Void
    private let item = NSRegularExpression(#"^\s*[-•*]\s+(.*)$"#, ic: false)

    var body: some View {
        Janela(largura: 520, fechar: fechar) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("NOVIDADES").font(.system(size: 12, weight: .bold)).foregroundColor(Cor.vermelhoClaro)
                    Text("O que chegou de novo").font(.system(size: 22, weight: .bold)).foregroundColor(.white).padding(.top, 2)
                    ForEach(lista) { n in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(Novidades.dataPorExtenso(n.data)).font(.system(size: 13, weight: .semibold)).foregroundColor(Cor.vermelhoClaro)
                            Text(n.titulo).font(.system(size: 18, weight: .bold)).foregroundColor(.white).padding(.top, 3).padding(.bottom, 4)
                            // como no site: linha com "-" vira item de lista; linha em branco separa parágrafos
                            ForEach(Array(n.texto.replacingOccurrences(of: "\r", with: "").components(separatedBy: "\n").enumerated()), id: \.offset) { _, linha in
                                if linha.trimmingCharacters(in: .whitespaces).isEmpty {
                                    Spacer().frame(height: 6)
                                } else if let m = item.grupos(linha) {
                                    Text("•  " + (m[1] ?? "").trimmingCharacters(in: .whitespaces)).font(.system(size: 15))
                                        .foregroundColor(Color(hex: 0xC4C4CC)).padding(.leading, 8).padding(.top, 2)
                                } else {
                                    Text(linha.trimmingCharacters(in: .whitespaces)).font(.system(size: 15)).foregroundColor(Color(hex: 0xC4C4CC)).padding(.top, 2)
                                }
                            }
                        }
                        .padding(.top, 18)
                    }
                    HStack(spacing: 10) {
                        Botao("Entendi", primario: true, acao: fechar)
                        Botao("Ver no site") { if let u = URL(string: Novidades.site) { UIApplication.shared.open(u) } }
                    }
                    .padding(.top, 20)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: 560)
        }
    }
}

/// Tela de abertura com o logotipo (enquanto confere a conta e carrega a lista).
struct Abertura: View {
    @ObservedObject var e = Estado.s
    @Environment(\.medidas) var m
    @State private var anim = false
    @State private var texto = false

    var body: some View {
        if e.abertura {
            ZStack {
                Cor.fundo.ignoresSafeArea()
                VStack(spacing: 28) {
                    Logo(altura: m.largo ? 60 : 36)
                        .scaleEffect(anim ? 1 : 0.86)
                        .opacity(anim ? 1 : 0)
                    Group {
                        // carregando a lista: só a barra com a %, sem texto
                        if e.andamento >= 0 && e.textoAbertura.isEmpty {
                            VStack(spacing: 12) {
                                Text("\(Int((e.andamento * 100).rounded()))%").font(.system(size: 15)).foregroundColor(Cor.sub)
                                BarraAndamento(p: e.andamento).frame(width: m.largo ? 320 : 220)
                            }
                        } else {
                            Text(e.textoAbertura).font(.system(size: 15)).foregroundColor(Cor.sub).multilineTextAlignment(.center)
                        }
                    }
                    .opacity(texto ? 1 : 0)
                }
            }
            .transition(.opacity.animation(.easeOut(duration: 0.38)))
            .onAppear {
                withAnimation(.easeOut(duration: 1.1)) { anim = true }
                withAnimation(.easeIn(duration: 0.4).delay(0.9)) { texto = true }
            }
        }
    }
}

/// Aviso rápido no rodapé (some sozinho depois de 3,5 s).
struct Avisos: View {
    @ObservedObject var e = Estado.s

    var body: some View {
        VStack {
            Spacer()
            if let a = e.aviso {
                Text(a.texto)
                    .font(.system(size: 15))
                    .foregroundColor(.white)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
                    .background(RoundedRectangle(cornerRadius: 8).fill(Color(hex: 0x262626, alpha: 0.94)))
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color(hex: 0x3A3A3A), lineWidth: 1))
                    .frame(maxWidth: 640)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 40)
                    .transition(.opacity)
                    .id(a.n)
                    .task(id: a.n) {
                        if a.fixo { return }
                        try? await Task.sleep(nanoseconds: 3_500_000_000)
                        if e.aviso?.n == a.n { withAnimation { e.aviso = nil } }
                    }
            }
        }
        .allowsHitTesting(false)
        .animation(.easeOut(duration: 0.25), value: e.aviso?.n)
    }
}

struct BannerAtualizacao: View {
    @ObservedObject var e = Estado.s

    var body: some View {
        if let v = e.novaVersao, e.bannerAtualizacao, e.tela == .principal, e.player == nil, e.detalhes == nil, !e.ajustes {
            VStack {
                Spacer()
                HStack {
                    Spacer()
                    VStack(alignment: .leading, spacing: 0) {
                        Text("StreamPLAY \(Atualizador.rotulo(v.versao)) disponível").font(.system(size: 18, weight: .bold)).foregroundColor(.white)
                        Text("Tem uma versão nova do app. Baixe o arquivo .ipa novo e instale por cima, do mesmo jeito que você instalou.")
                            .font(.system(size: 14)).foregroundColor(Cor.sub).padding(.top, 6).padding(.bottom, 14)
                        HStack(spacing: 10) {
                            Botao("Baixar agora", primario: true) { e.instalarAtualizacao() }
                            Botao("Depois") { e.bannerAtualizacao = false }
                        }
                    }
                    .padding(20)
                    .frame(maxWidth: 420, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 8).fill(Color(hex: 0x202020, alpha: 0.95)))
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(Cor.linha, lineWidth: 1))
                }
                .padding(24)
            }
        }
    }
}
