import SwiftUI

struct TelaAjustes: View {
    @Environment(\.medidas) var m
    @ObservedObject private var e = Estado.s
    @State private var confirmar: String?
    @State private var autoNext = Store.s.autoNext
    @State private var cache = Store.s.caching

    var body: some View {
        let src = Store.s.source ?? ""
        let arquivo = !Fonte.isUrl(src)
        let conta = e.contaAtual?.acesso
        ZStack(alignment: .top) {
            Color.black.opacity(0.7).ignoresSafeArea().onTapGesture { e.fecharAjustes() }
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    HStack {
                        Text("Configurações").font(.system(size: 26, weight: .bold)).foregroundColor(.white)
                        Spacer()
                        IconeBotao(icone: Icone.fechar, fundo: Color(hex: 0x2A2A2A)) { e.fecharAjustes() }
                    }

                    secao("Conta")
                    Text("Conectado como \(Store.s.login ?? "")").font(.system(size: 16)).foregroundColor(.white)
                    Text(resumoConta(conta)).font(.system(size: 14)).foregroundColor(Cor.sub).padding(.top, 2)
                    Botao("Sair da conta") { confirmar = "sair" }.padding(.top, 12)

                    secao("Sua lista")
                    // o link da lista online não aparece (é do painel, não do cliente); arquivo do aparelho mostra o nome
                    if arquivo { Text(Fonte.nomeAmigavel(src)).font(.system(size: 14)).foregroundColor(.white).lineLimit(2).padding(.bottom, 4) }
                    Text(e.lib?.summary ?? "").font(.system(size: 14)).foregroundColor(Cor.sub)
                    Text(arquivo ? "● Arquivo do aparelho: para atualizar, escolha o arquivo de novo em Trocar lista."
                         : "● Lista online: o app confere novidades sozinho a cada 10 minutos (e quando você volta para ele).")
                        .font(.system(size: 13)).foregroundColor(Cor.verde).padding(.top, 6)
                    HStack(spacing: 10) {
                        Botao("Recarregar agora") { e.recarregar() }
                        if !e.listaDoPainel { Botao("Trocar lista") { e.mostrarFonte(nil) } }
                    }
                    .padding(.top, 12)

                    secao("Reprodução")
                    Toggle(isOn: $autoNext) {
                        Text("Tocar o próximo episódio automaticamente").font(.system(size: 15)).foregroundColor(.white)
                    }
                    .tint(Cor.vermelho)
                    .onChange(of: autoNext) { v in Store.s.autoNext = v; Store.s.save() }
                    Text("Tempo de carregamento (aumente se o vídeo ficar travando)").font(.system(size: 14)).foregroundColor(Cor.sub)
                        .padding(.top, 14).padding(.bottom, 8)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach([1000, 1500, 3000, 5000, 8000], id: \.self) { ms in
                                Chip(texto: (ms % 1000 == 0 ? "\(ms / 1000)" : "\(ms / 1000),\((ms % 1000) / 100)") + " s", selecionado: cache == ms) {
                                    cache = ms
                                    Store.s.caching = ms
                                    Store.s.save()
                                }
                            }
                        }
                    }
                    Botao("Limpar \"Continuar assistindo\"") { confirmar = "progresso" }.padding(.top, 16)

                    secao("Atualizações")
                    Text(e.statusAtualizacao.isEmpty
                         ? "Versão \(Atualizador.rotulo(Atualizador.atual)). O app procura atualizações sozinho ao abrir e a cada 30 minutos."
                         : e.statusAtualizacao)
                        .font(.system(size: 14)).foregroundColor(Cor.sub)
                    if let nova = e.novaVersao {
                        Botao("Baixar a versão \(Atualizador.rotulo(nova.versao))") { e.instalarAtualizacao() }.padding(.top, 10)
                    } else {
                        Botao("Procurar atualizações") { Task { await e.procurarAtualizacao(manual: true) } }.padding(.top, 10)
                    }

                    Text("StreamPLAY \(Atualizador.rotulo(Atualizador.atual))" + (Vlc.versao.map { "  •  motor VLC \($0)" } ?? ""))
                        .font(.system(size: 12)).foregroundColor(Cor.dim).padding(.top, 24)
                }
                .padding(24)
                .frame(maxWidth: m.largo ? 760 : .infinity, alignment: .leading)
                .background(Cor.janela)
                .clipShape(RoundedRectangle(cornerRadius: m.largo ? 8 : 0))
                .frame(maxWidth: .infinity)
                .padding(.top, m.largo ? 24 : 0)
            }
        }
        .alert(confirmar == "sair" ? "Sair da sua conta? Para usar o StreamPLAY de novo, será preciso digitar o login e a senha."
               : "Apagar o progresso de tudo que você estava assistindo?",
               isPresented: Binding(get: { confirmar != nil }, set: { if !$0 { confirmar = nil } })) {
            Button("Cancelar", role: .cancel) { confirmar = nil }
            Button(confirmar == "sair" ? "Sair" : "Apagar", role: .destructive) {
                let c = confirmar
                confirmar = nil
                if c == "sair" { e.sairDaConta(nil) } else { limparProgresso() }
            }
        }
    }

    private func secao(_ t: String) -> some View {
        Text(t).font(.system(size: 13, weight: .bold)).foregroundColor(Cor.dim).padding(.top, 26).padding(.bottom, 8)
    }

    private func resumoConta(_ c: Conta.Acesso?) -> String {
        var l: [String] = []
        if let p = c?.plano, !p.trimmingCharacters(in: .whitespaces).isEmpty { l.append(p) }
        if let v = c?.faturaVenc { l.append("próximo vencimento \(Dia.fmt(v)) (\(Conta.dinheiro(c?.faturaValor)))") }
        let s = l.joined(separator: "  •  ")
        return s.isEmpty ? "Acesso liberado." : s.prefix(1).uppercased() + s.dropFirst()
    }

    private func limparProgresso() {
        let st = Store.s
        st.progress = [:]
        // os outros aparelhos da conta também limpam (a marca vai junto na sincronização)
        st.progressoLimpo = agoraMs()
        Sincronia.sujo = true
        st.save()
        e.enviarSincronia()
        e.toast("\"Continuar assistindo\" foi limpo")
    }
}
