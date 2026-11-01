import SwiftUI
import UniformTypeIdentifiers

let guiaLista =
    "FILMES  →  group-title=\"FILMES\"\n" +
    "LANÇAMENTOS  →  group-title=\"FILMES | Lançamentos\"\n" +
    "Outras fileiras  →  group-title=\"FILMES | Ação\"\n\n" +
    "SÉRIES  →  group-title=\"SERIES | Drama\"\n" +
    "   e o nome do episódio assim: \"Nome da Série S01E01\"\n\n" +
    "CANAIS  →  group-title=\"CANAIS = Esportes\"  (ou \"CANAIS | Esportes\")\n" +
    "   os canais também podem ficar num canais.m3u ao lado da lista (no mesmo link)\n" +
    "   (qualquer outro grupo também vira canal)\n\n" +
    "Exemplo:\n" +
    "#EXTINF:-1 tvg-name=\"Nome do filme\" tvg-logo=\"http://capa.jpg\" group-title=\"FILMES | Lançamentos\"\n" +
    "http://link-do-video.mp4\n\n" +
    "Opcional na linha #EXTINF: descricao=\"...\" ano=\"2024\" genero=\"Ação\" nota=\"8.5\" fundo=\"http://imagem-larga.jpg\""

/// Coluna centralizada com rolagem (telas de login, bloqueio e lista).
private struct Cartaz<C: View>: View {
    var largura: CGFloat = 440
    @ViewBuilder let conteudo: () -> C

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) { conteudo() }
                .frame(maxWidth: largura, alignment: .leading)
                .padding(24)
                .frame(maxWidth: .infinity)
        }
    }
}

struct Campo: View {
    let rotulo: String
    @Binding var valor: String
    var senha = false
    var teclado: UIKeyboardType = .default
    var acao: SubmitLabel = .next
    var aoConfirmar: () -> Void = {}

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(rotulo).font(.system(size: 13)).foregroundColor(Cor.dim)
            Group {
                if senha { SecureField("", text: $valor) } else { TextField("", text: $valor) }
            }
            .textInputAutocapitalization(.never)
            .disableAutocorrection(true)
            .keyboardType(teclado)
            .submitLabel(acao)
            .onSubmit(aoConfirmar)
            .foregroundColor(.white)
            .font(.system(size: 17))
            .padding(.horizontal, 14)
            .frame(height: 50)
            .background(RoundedRectangle(cornerRadius: 6).fill(Color(hex: 0x1C1C1C)))
            .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color(hex: 0x6A6A6A), lineWidth: 1))
        }
    }
}

/// Tela de entrada com a conta do PLAYGER.
struct TelaLogin: View {
    let aviso: String?
    @State private var login = Store.s.login ?? ""
    @State private var senha = ""
    @State private var erro: String?
    @State private var ocupado = false
    @FocusState private var foco: Int?

    var body: some View {
        Cartaz {
            Logo(altura: 32).padding(.top, 40)
            Text("Entrar").font(.system(size: 28, weight: .bold)).foregroundColor(.white).padding(.top, 28)
            Text("Use o login e a senha que você recebeu.").font(.system(size: 15)).foregroundColor(Cor.sub).padding(.top, 6).padding(.bottom, 20)
            Campo(rotulo: "Login", valor: $login) { if senha.isEmpty { foco = 2 } else { entrar() } }.focused($foco, equals: 1)
            Campo(rotulo: "Senha", valor: $senha, senha: true, acao: .done) { entrar() }.focused($foco, equals: 2).padding(.top, 12)
            if let e = erro ?? aviso { Text(e).font(.system(size: 14)).foregroundColor(Color(hex: 0xFF6B6B)).padding(.top, 12) }
            Botao(ocupado ? "Verificando..." : "Entrar", primario: true, habilitado: !ocupado, cheio: true) { entrar() }.padding(.top, 22)
        }
    }

    private func entrar() {
        if ocupado { return }
        let l = login.trimmingCharacters(in: .whitespaces)
        if l.isEmpty { erro = "Digite o seu login."; return }
        if senha.isEmpty { erro = "Digite a sua senha."; return }
        ocupado = true
        erro = nil
        foco = nil
        Task {
            let id = Conta.idAcesso(l, senha)
            let r = await Conta.verificar(id)
            ocupado = false
            switch r.estado {
            case .invalido: erro = "Login ou senha incorretos."; senha = ""
            case .semConexao: erro = r.mensagem
            default:
                // login e senha certos: guarda para entrar sozinho da próxima vez (mesmo bloqueado, para "verificar de novo")
                Store.s.login = l.lowercased()
                Store.s.acessoId = id
                Store.s.save()
                if r.estado == .bloqueado { Estado.s.contaBloqueada(r) } else { Estado.s.contaLiberada(r) }
            }
        }
    }
}

/// Login certo, mas o acesso está bloqueado: mostra o motivo, o Pix e "já paguei".
struct TelaBloqueio: View {
    let r: Conta.Resultado
    @State private var ocupado = false
    @State private var status: String?
    @State private var px: Conta.Pix?
    @State private var pixErro: String?

    private var porPagamento: Bool { r.acesso?.ativo == true && r.acesso?.manual == false && !r.aparelhos }
    private var pixUrl: String? { r.acesso?.pixUrl.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 } }
    private var auto: Bool { porPagamento && pixUrl != nil }

    var body: some View {
        Cartaz(largura: 480) {
            Logo(altura: 32).padding(.top, 40)
            Text(r.titulo).font(.system(size: 26, weight: .bold)).foregroundColor(.white).padding(.top, 28)
            Text(r.mensagem).font(.system(size: 16)).foregroundColor(Cor.sub).lineSpacing(5).padding(.top, 10)
            if auto && pixErro == nil {
                VStack(alignment: .leading, spacing: 0) {
                    if let p = px {
                        if !p.pendente {
                            Text("Pagamento recebido. Liberando o acesso...").font(.system(size: 16)).foregroundColor(Cor.sub)
                        } else {
                            Text("Pague com Pix: \(Conta.dinheiro(p.valor))" + (p.faturas > 1 ? "  (\(p.faturas) faturas)" : ""))
                                .font(.system(size: 18, weight: .bold)).foregroundColor(.white)
                            Text("Copie o código e cole no app do seu banco (Pix copia e cola). Também dá para pagar o QR Code por outro celular.")
                                .font(.system(size: 14)).foregroundColor(Cor.sub).padding(.top, 8)
                            if let c = p.copia {
                                Text(c).font(.system(size: 12, design: .monospaced)).foregroundColor(Cor.dim).lineLimit(2).padding(.top, 12)
                                Botao("Copiar código Pix", primario: true) {
                                    UIPasteboard.general.string = c
                                    Estado.s.toast("Código Pix copiado. Cole no app do seu banco (Pix copia e cola).")
                                }
                                .padding(.top, 8)
                            }
                            if let q = p.qr, let d = Data(base64Encoded: q, options: .ignoreUnknownCharacters), let img = UIImage(data: d) {
                                Image(uiImage: img).interpolation(.none).resizable().scaledToFit()
                                    .frame(width: 190, height: 190).padding(10)
                                    .background(RoundedRectangle(cornerRadius: 6).fill(Color.white))
                                    .frame(maxWidth: .infinity)
                                    .padding(.top, 14)
                            }
                            Text("O acesso é liberado sozinho assim que o pagamento for confirmado.").font(.system(size: 13)).foregroundColor(Cor.verde).padding(.top, 12)
                        }
                    } else {
                        Text("Gerando o Pix...").font(.system(size: 16)).foregroundColor(Cor.sub)
                    }
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 6).fill(Color(hex: 0x1F1F1F)))
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(Cor.linha, lineWidth: 1))
                .padding(.top, 20)
            } else if porPagamento, let pix = r.acesso?.pix, !pix.trimmingCharacters(in: .whitespaces).isEmpty {
                if let e = pixErro { Text("Não consegui gerar o QR Code do Pix agora: \(e)").font(.system(size: 14)).foregroundColor(Cor.sub).padding(.top, 14) }
                VStack(alignment: .leading, spacing: 0) {
                    Text("Chave Pix").font(.system(size: 13)).foregroundColor(Cor.dim)
                    Text(pix).font(.system(size: 17, weight: .semibold)).foregroundColor(.white).padding(.top, 4).padding(.bottom, 12)
                    Botao("Copiar chave Pix") {
                        UIPasteboard.general.string = pix
                        Estado.s.toast("Chave Pix copiada")
                    }
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 6).fill(Color(hex: 0x1F1F1F)))
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(Cor.linha, lineWidth: 1))
                .padding(.top, 20)
            }
            Botao(ocupado ? "Verificando..." : porPagamento ? "Já paguei, verificar de novo" : "Verificar de novo", primario: true, habilitado: !ocupado, cheio: true) {
                verificar()
            }
            .padding(.top, 22)
            if let s = status { Text(s).font(.system(size: 14)).foregroundColor(Cor.sub).padding(.top, 12) }
            Botao("Entrar com outra conta", cheio: true) { Estado.s.sairDaConta(nil) }.padding(.top, 12)
        }
        // Pix automático ligado no painel: QR Code com o valor certo no lugar da chave
        .task {
            guard auto, let u = pixUrl, let id = Store.s.acessoId else { return }
            do { px = try await Conta.pix(u, id) } catch let e as Conta.ErroPix { pixErro = e.mensagem } catch { pixErro = "sem resposta do servidor." }
        }
        // com o Pix na tela, confere sozinho se o pagamento caiu (a cada 10 s; depois de 20 min, a cada minuto)
        .task {
            guard auto else { return }
            let inicio = relogio()
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: relogio() - inicio > 20 * 60_000 ? 60_000_000_000 : 10_000_000_000)
                guard !Task.isCancelled, let id = Store.s.acessoId else { break }
                if ocupado { continue }
                let r2 = await Conta.verificar(id)
                if r2.estado == .liberado && !ocupado {
                    Estado.s.toast("Pagamento confirmado! Bom proveito.")
                    Estado.s.contaLiberada(r2)
                    break
                }
            }
        }
    }

    private func verificar() {
        guard !ocupado, let id = Store.s.acessoId, !id.isEmpty else { return }
        ocupado = true
        status = nil
        Task {
            let r2 = await Conta.verificar(id)
            ocupado = false
            switch r2.estado {
            case .liberado: Estado.s.contaLiberada(r2)
            case .bloqueado:
                Estado.s.contaBloqueada(r2)
                status = "Ainda não foi liberado. Assim que o pagamento for confirmado, tente de novo."
            case .invalido: Estado.s.sairDaConta("Sua senha mudou ou o acesso foi removido. Entre de novo.")
            default: status = r2.mensagem
            }
        }
    }
}

/// Escolher a lista: link (http/https) ou arquivo do aparelho.
struct TelaFonte: View {
    let erro: String?
    @ObservedObject private var e = Estado.s
    @State private var url = ""
    @State private var erroAqui: String?
    @State private var carregando = false
    @State private var escolhendo = false

    var body: some View {
        let podeVoltar = e.lib != nil && erro == nil
        Cartaz(largura: 640) {
            if podeVoltar {
                HStack(spacing: 6) {
                    IconeBotao(icone: Icone.voltar) { e.sairDaFonte() }
                    Text("Voltar").font(.system(size: 15)).foregroundColor(Cor.sub)
                }
                .padding(.bottom, 8)
            }
            Logo(altura: 32).padding(.top, podeVoltar ? 0 : 40)
            Text("Escolha sua lista").font(.system(size: 28, weight: .bold)).foregroundColor(.white).padding(.top, 24)
            Text("Cole o link da sua lista M3U (ou escolha um arquivo do aparelho). O StreamPLAY separa sozinho Canais, Filmes e Séries.")
                .font(.system(size: 15)).foregroundColor(Cor.sub).padding(.top, 6).padding(.bottom, 20)
            Campo(rotulo: "Link da lista (http:// ou https://)", valor: $url, teclado: .URL, acao: .go) { usarLink() }
            HStack(spacing: 10) {
                Botao("Usar este link", primario: true, habilitado: !carregando) { usarLink() }
                Botao("Escolher arquivo", habilitado: !carregando) { escolhendo = true }
            }
            .padding(.top, 14)
            if carregando && (erroAqui ?? erro) == nil {
                HStack(spacing: 12) {
                    BarraAndamento(p: max(0, e.andamento)).frame(width: 240)
                    Text("\(Int((max(0, e.andamento) * 100).rounded()))%").font(.system(size: 14)).foregroundColor(Cor.sub)
                }
                .padding(.top, 16)
            }
            if let er = erroAqui ?? (carregando ? nil : erro) { Text(er).font(.system(size: 14)).foregroundColor(Color(hex: 0xFF6B6B)).padding(.top, 14) }
            Text("Como organizar sua lista").font(.system(size: 17, weight: .bold)).foregroundColor(.white).padding(.top, 30).padding(.bottom, 8)
            Text(guiaLista).font(.system(size: 12, design: .monospaced)).foregroundColor(Cor.sub).lineSpacing(4)
        }
        .fileImporter(isPresented: $escolhendo, allowedContentTypes: [.item]) { r in
            guard case .success(let u) = r else { return }
            let ok = u.startAccessingSecurityScopedResource()
            defer { if ok { u.stopAccessingSecurityScopedResource() } }
            do {
                // o iOS só deixa ler o arquivo agora: o app guarda uma cópia
                let d = try Data(contentsOf: u)
                try d.write(to: Fonte.arquivoEscolhido, options: .atomic)
                erroAqui = nil
                carregando = true
                e.usarFonte(Fonte.prefixoArquivo + u.lastPathComponent)
            } catch {
                erroAqui = "Não consegui ler o arquivo: \(mensagemErro(error))"
            }
        }
    }

    private func usarLink() {
        let u = url.trimmingCharacters(in: .whitespaces)
        if !M3U.isUrl(u) { erroAqui = "Cole um link que comece com http:// ou https://"; return }
        erroAqui = nil
        carregando = true
        e.usarFonte(u)
    }
}
