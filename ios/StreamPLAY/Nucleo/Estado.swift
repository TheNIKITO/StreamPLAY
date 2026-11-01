import Foundation
import UIKit

enum Tela: Equatable {
    case abertura
    case login(String?)
    case bloqueio(Int)       // número do resultado guardado em Estado.bloqueio (Resultado não é Equatable)
    case fonte(String?, Int) // o número muda a cada vez: o mesmo erro duas vezes seguidas ainda é uma tela nova
    case principal
}

struct Aviso: Equatable {
    let texto: String
    let fixo: Bool
    let n: Int
}

/// Estado do app e as regras da janela principal (iguais ao Estado.kt do Android): conta, lista, navegação e player.
@MainActor
final class Estado: ObservableObject {
    static let s = Estado()

    @Published var tela: Tela = .abertura
    @Published var abertura = true
    @Published var textoAbertura = ""
    /// Barra da lista carregando (0 a 1) na abertura e em "Escolha sua lista"; -1 = sem barra.
    @Published var andamento: Double = -1
    @Published var lib: Library?
    @Published var tab = "home"
    @Published var detalhes: Item?
    @Published var temporada = 0
    @Published var ajustes = false
    @Published var player: PlayRequest?
    @Published var busca = ""
    /// Muda quando a aba deve voltar ao começo (saiu de um título aberto pela busca).
    @Published var voltarAoInicio = 0
    @Published var aviso: Aviso?
    /// Novidades do PLAYGER para a janela de novidades (vazia = fechada).
    @Published var novidades: [Novidades.Novidade] = []
    @Published var contaAtual: Conta.Resultado?
    private(set) var bloqueio: Conta.Resultado?
    private var nTela = 0
    private var nAviso = 0

    /// Avisos do sino (texto, quando), o mais novo primeiro; avisosNovos acende a bolinha vermelha.
    @Published var avisos: [(String, String)] = []
    @Published var avisosNovos = false
    /// Janela aberta pela barra de cima: "avisos" ou "conta".
    @Published var janela: String?

    @Published var novaVersao: Atualizador.Versao?
    @Published var bannerAtualizacao = false
    @Published var statusAtualizacao = ""

    private var novidadesConferidas = false
    private var iniciado = false
    private var carregandoLista = false
    private var checandoLista = false
    private var verificandoConta = false
    private var pendente: Library?
    private var ultimaConta: Int64 = 0
    private var ultimaLista: Int64 = 0
    private var ultimaAtualizacao: Int64 = 0
    private var ultimoGuia: Int64 = 0
    private var estaNaFrente = false
    private var onlineId: String?   // conta que está aparecendo como online no painel
    private var ultimoSinal: Int64 = 0
    private var ultimoEnvio: Int64 = 0
    private var laco: Task<Void, Never>?
    private var tarefaDeFundo = UIBackgroundTaskIdentifier.invalid

    private let umMin: Int64 = 60_000
    private var onlineCada: Int64 { 5 * umMin }
    private var contaCada: Int64 { 15 * umMin }
    private var listaCada: Int64 { 10 * umMin }
    private var atualizaCada: Int64 { 30 * umMin }
    // a grade anda e o arquivo do guia é renovado a cada 6 h: relê de tempos em tempos
    private var guiaCada: Int64 { 30 * umMin }
    // sem internet, quem já entrou continua usando por até 3 dias desde a última confirmação
    private var tolerancia: Int64 { 3 * 24 * 60 * umMin }

    private var agora: Int64 { agoraMs() }
    var naTelaDeConta: Bool {
        switch tela { case .login, .bloqueio: return true; default: return false }
    }

    func avisar(_ texto: String) {
        if texto.trimmingCharacters(in: .whitespaces).isEmpty || avisos.first?.0 == texto { return }
        let f = DateFormatter()
        f.locale = ptBR
        f.dateFormat = "dd/MM 'às' HH:mm"
        avisos.insert((texto, f.string(from: Date())), at: 0)
        if avisos.count > 15 { avisos.removeLast(avisos.count - 15) }
        avisosNovos = true
    }

    func iniciar() {
        if iniciado { return }
        iniciado = true
        tab = Store.s.tab
        Conta.definirAparelho()
        // teste grátis: um teste por aparelho (o servidor só recebe o hash)
        Conta.definirIdForte()
        ultimaConta = agora
        ultimaLista = agora
        ultimaAtualizacao = agora
        Task {
            let inicio = relogio()
            if await garantirConta() { await carregarLista() }
            let espera = 1150 - (relogio() - inicio)
            if espera > 0 { try? await Task.sleep(nanoseconds: UInt64(espera) * 1_000_000) }
            abertura = false
            if !naTelaDeConta { await procurarAtualizacao(manual: false) }
        }
    }

    /// O app foi para a frente (true) ou saiu dela (false).
    func naFrente(_ sim: Bool) {
        if sim {
            if !estaNaFrente {
                estaNaFrente = true
                atualizarOnline()
                Task { await aoVoltar() }
                laco?.cancel()
                // com o app na frente: confere conta, lista e atualização de tempos em tempos
                laco = Task {
                    while !Task.isCancelled {
                        try? await Task.sleep(nanoseconds: 60_000_000_000)
                        if Task.isCancelled { break }
                        await periodico()
                    }
                }
            }
        } else if estaNaFrente {
            estaNaFrente = false
            laco?.cancel()
            laco = nil
            // o iOS dá alguns segundos para avisar o painel e mandar o que mudou antes de congelar o app
            encerrarTarefaDeFundo()
            tarefaDeFundo = UIApplication.shared.beginBackgroundTask { [weak self] in self?.encerrarTarefaDeFundo() }
            atualizarOnline()
            Task {
                try? await Task.sleep(nanoseconds: 8_000_000_000)
                if !estaNaFrente { encerrarTarefaDeFundo() }
            }
        }
    }

    private func encerrarTarefaDeFundo() {
        if tarefaDeFundo == .invalid { return }
        UIApplication.shared.endBackgroundTask(tarefaDeFundo)
        tarefaDeFundo = .invalid
    }

    /// Ao voltar para o app: confere conta, lista e atualização se já faz um tempo.
    func aoVoltar() async {
        if !iniciado { return }
        if agora - ultimaConta > 10 * umMin { await conferirConta() }
        if agora - ultimaLista > 3 * umMin { await conferirLista() }
        if agora - ultimaAtualizacao > atualizaCada { await procurarAtualizacao(manual: false) }
    }

    // Aparece como online no painel enquanto o app está na frente com a conta liberada; renova a cada 5 min.
    private func atualizarOnline() {
        let id = estaNaFrente && contaAtual != nil ? Store.s.acessoId : nil
        if id == onlineId {
            if let id = id, agora - ultimoSinal >= onlineCada {
                sinal(id, true)
                // parado nas telas, confere o que os outros aparelhos assistiram; vendo algo, manda a cada 10 min
                if player == nil { baixarSincronia(id) } else if agora - ultimoEnvio >= 10 * umMin { enviarSincronia() }
            }
            return
        }
        if let antigo = onlineId { sinal(antigo, false); enviarSincronia(antigo) }   // saiu da frente: manda o que mudou
        onlineId = id
        if let id = id {
            sinal(id, true)
            if Sincronia.sujo { enviarSincronia() } else { baixarSincronia(id) }
        }
    }

    // ---------- Sincronização (continuar assistindo e Minha lista entre os aparelhos da conta) ----------

    private func baixarSincronia(_ id: String) {
        Task {
            _ = await Sincronia.baixar(id)
            if Sincronia.sujo { enviarSincronia(id) }
        }
    }

    /// Manda progresso e Minha lista para a nuvem (se mudou algo). Ao pausar, ao sair do player, ao favoritar.
    func enviarSincronia(_ id0: String? = nil) {
        guard let id = id0 ?? onlineId, Sincronia.sujo else { return }
        ultimoEnvio = agora
        Task { _ = await Sincronia.enviar(id) }
    }

    private func sinal(_ id: String, _ online: Bool) {
        if online { ultimoSinal = agora }
        Task { await Conta.sinal(id, online) }
    }

    func periodico() async {
        if !iniciado { return }
        atualizarOnline()
        // teste grátis: confere a conta logo que o teste acaba (a conferência normal é só a cada 15 min)
        let fimTeste = contaAtual?.acesso?.teste?.fim
        if agora - ultimaConta >= contaCada || (fimTeste != nil && agora >= fimTeste! + 5000 && agora - ultimaConta > 30_000) { await conferirConta() }
        if agora - ultimaLista >= listaCada { await conferirLista() }
        if agora - ultimoGuia >= guiaCada { carregarGuia() }
        if agora - ultimaAtualizacao >= atualizaCada { await procurarAtualizacao(manual: false) }
    }

    // ---------- Lista ----------

    func carregarLista(quiet: Bool = false) async {
        guard let fonte = Store.s.source, !fonte.trimmingCharacters(in: .whitespaces).isEmpty else { mostrarFonte(nil); return }
        if carregandoLista { return }
        carregandoLista = true
        defer { carregandoLista = false; ultimaLista = agora }
        if !quiet { textoAbertura = ""; andamento = 0 }
        do {
            let nova = try await Task.detached(priority: .userInitiated) {
                try await Fonte.carregar(fonte) { p in Task { @MainActor in if !quiet { Estado.s.andamento = p } } }
            }.value
            Store.s.listaSalva = fonte
            Store.s.save()
            definirLista(nova)
            if quiet { toast("Lista atualizada  •  " + nova.summary); avisar("Lista atualizada  •  " + nova.summary) }
            else { mostrarNovidades() }
        } catch {
            let msg = mensagemErro(error)
            var salva: Library? = nil
            if !quiet && Store.s.listaSalva == fonte {
                salva = await Task.detached(priority: .userInitiated) { Fonte.carregarSalva(fonte) }.value
            }
            if let salva = salva {
                definirLista(salva)
                toast("Não consegui baixar a lista agora (\(msg)). Mostrando a última cópia salva.")
            } else if quiet && lib != nil {
                toast("Não consegui recarregar a lista: \(msg)")
            } else {
                mostrarFonte("Não foi possível abrir a lista: \(msg)")
            }
        }
    }

    // Novidades do PLAYGER: na abertura, já na tela da lista, mostra numa janela cada novidade nova (uma vez só).
    private func mostrarNovidades() {
        if novidadesConferidas { return }
        novidadesConferidas = true
        Task {
            guard let l = try? await Novidades.novas(), !l.isEmpty else { return }
            try? await Task.sleep(nanoseconds: 1_600_000_000)   // deixa a abertura sumir e a lista aparecer primeiro
            if tela != .principal || player != nil || detalhes != nil || ajustes { novidadesConferidas = false; return }
            Novidades.marcarVistas(l)
            for n in l.reversed() { avisar("Novidade: \(n.titulo)") }
            novidades = l
        }
    }

    private func definirLista(_ nova: Library) {
        lib = nova
        carregarGuia()
        pendente = nil
        if naTelaDeConta { return }   // lista recarregou por trás da tela de login: só aparece depois de entrar
        tela = .principal
    }

    /// Lista online: confere novidades a cada 10 min e ao voltar para o app.
    private func conferirLista() async {
        guard let fonte = Store.s.source, !fonte.isEmpty, !checandoLista, !carregandoLista, lib != nil else { return }
        checandoLista = true
        ultimaLista = agora
        defer { checandoLista = false }
        let nova = try? await Task.detached(priority: .utility) { try await Fonte.carregarSeMudou(fonte) }.value
        if let nova = nova, fonte == Store.s.source { aplicarNova(nova) }
    }

    private func aplicarNova(_ nova: Library) {
        // não troca a tela embaixo de quem está com os detalhes abertos, buscando ou assistindo: aplica depois
        if detalhes != nil || !busca.isEmpty || player != nil { pendente = nova; return }
        definirLista(nova)
        toast("Lista atualizada  •  " + nova.summary)
        avisar("Lista atualizada  •  " + nova.summary)
    }

    /// Guia de programação dos canais (em segundo plano).
    private func carregarGuia() {
        guard let l = lib else { return }
        ultimoGuia = agora
        Task { await Guia.s.carregar(l) }
    }

    private func aplicarPendente() {
        if let p = pendente { aplicarNova(p) }
    }

    func mostrarFonte(_ erro: String?) {
        if naTelaDeConta { return }
        detalhes = nil
        ajustes = false
        nTela += 1
        tela = .fonte(erro, nTela)
    }

    func sairDaFonte() {
        if lib == nil || naTelaDeConta { return }
        tela = .principal
    }

    func usarFonte(_ fonte: String) {
        andamento = 0
        Store.s.source = fonte
        Store.s.save()
        Task { await carregarLista() }
    }

    func recarregar() {
        ajustes = false
        Task { await carregarLista(quiet: true) }
    }

    // ---------- Conta (PLAYGER) ----------

    /// Na abertura: confere a conta salva. Devolve false quando a tela de login ficou na frente.
    private func garantirConta() async -> Bool {
        guard let id = Store.s.acessoId, !id.isEmpty else { mostrarLogin(.login(nil)); return false }
        textoAbertura = "Verificando seu acesso..."
        let r = await Conta.verificar(id)
        if r.estado == .liberado { _ = aceitarConta(r); avisarVencimento(r); return true }
        if r.estado == .semConexao && agora - Store.s.contaOk < tolerancia {
            ultimaConta = agora
            toast("Sem internet para confirmar seu acesso agora. Vou tentar de novo daqui a pouco.")
            return true
        }
        mostrarConta(r)
        return false
    }

    /// Conferência periódica (e ao voltar para o app): bloqueia na hora se o acesso caiu.
    private func conferirConta() async {
        guard let id = Store.s.acessoId, !id.isEmpty, !verificandoConta, !naTelaDeConta, !(contaAtual == nil && Store.s.contaOk == 0) else { return }
        verificandoConta = true
        ultimaConta = agora
        defer { verificandoConta = false }
        let r = await Conta.verificar(id)
        if naTelaDeConta { return }
        if r.estado == .liberado { if aceitarConta(r) { await carregarLista(quiet: true) } }
        else if r.estado != .semConexao || agora - Store.s.contaOk > tolerancia { mostrarConta(r) }
    }

    /// Guarda a conta liberada. Devolve true quando o painel mandou outra lista (aí a lista precisa ser recarregada).
    private func aceitarConta(_ r: Conta.Resultado) -> Bool {
        contaAtual = r
        let st = Store.s
        st.contaOk = agora
        if let l = r.acesso?.login, !l.isEmpty { st.login = l }
        // a lista vem do painel (PLAYGER) junto com o login: o cliente não digita nem vê o link
        let lista = r.acesso?.lista?.trimmingCharacters(in: .whitespaces) ?? ""
        let mudou = !lista.isEmpty && Fonte.isUrl(lista) && lista != st.source
        if mudou { st.source = lista }
        st.save()
        ultimaConta = agora
        atualizarOnline()
        return mudou
    }

    /// A lista é a que o painel manda com o login (o botão "Trocar lista" some).
    var listaDoPainel: Bool { !(contaAtual?.acesso?.lista ?? "").trimmingCharacters(in: .whitespaces).isEmpty }

    private func avisarVencimento(_ r: Conta.Resultado) {
        if let a = Conta.aviso(r) { toast(a); avisar(a) }
    }

    private func mostrarConta(_ r: Conta.Resultado) {
        switch r.estado {
        case .invalido: sairDaConta("Sua senha mudou ou o acesso foi removido. Entre de novo.")
        case .bloqueado: mostrarBloqueio(r)
        default: mostrarLogin(.login(r.mensagem))
        }
    }

    private func mostrarBloqueio(_ r: Conta.Resultado) {
        bloqueio = r
        nTela += 1
        mostrarLogin(.bloqueio(nTela))
    }

    private func mostrarLogin(_ t: Tela) {
        contaAtual = nil
        atualizarOnline()
        busca = ""
        detalhes = nil
        ajustes = false
        player = nil
        tela = t
    }

    /// A tela de login confirmou a conta (login certo ou "já paguei").
    func contaLiberada(_ r: Conta.Resultado) {
        let outraLista = aceitarConta(r)
        if lib != nil && !outraLista {
            tela = .principal
        } else {
            tela = .abertura
            abertura = true
            Task {
                await carregarLista()
                abertura = false
            }
        }
        avisarVencimento(r)
        Task { await procurarAtualizacao(manual: false) }
    }

    /// Login certo mas acesso bloqueado: fica na tela de bloqueio (com "verificar de novo").
    func contaBloqueada(_ r: Conta.Resultado) {
        bloqueio = r
        nTela += 1
        tela = .bloqueio(nTela)
    }

    func sairDaConta(_ aviso: String?) {
        Store.s.acessoId = nil
        Store.s.contaOk = 0
        Store.s.save()
        mostrarLogin(.login(aviso))
    }

    // ---------- Navegação ----------

    func navegar(_ t: String) {
        if lib == nil || naTelaDeConta { return }
        if tab == t { voltarAoInicio += 1 }   // tocar na aba aberta volta para o começo dela
        tab = t
        Store.s.tab = t
        busca = ""
    }

    func limparBusca() {
        busca = ""
        aplicarPendente()
    }

    func abrirDetalhes(_ i: Item) {
        temporada = 0
        detalhes = i
    }

    func fecharDetalhes() {
        detalhes = nil
        // saiu de um título aberto pela busca: a aba volta para o começo
        if !busca.isEmpty {
            busca = ""
            voltarAoInicio += 1
        }
        aplicarPendente()
    }

    func abrirAjustes() { ajustes = true }
    func fecharAjustes() { ajustes = false }

    // ---------- Player ----------

    func tocar(_ req: PlayRequest?) {
        guard let req = req, !(req.url ?? "").trimmingCharacters(in: .whitespaces).isEmpty else { toast("Este item não tem link de vídeo."); return }
        if naTelaDeConta { return }
        player = req
    }

    func fecharPlayer() {
        player = nil
        Store.s.save()
        enviarSincronia()   // saiu do player: o outro aparelho já pode continuar daqui
        aplicarPendente()
    }

    func abrirCartao(_ c: Cartao) {
        if let ep = c.ep { tocar(PlayRequest.forEpisode(ep)) }
        else if c.direct { tocar(PlayRequest.forItem(c.item)) }
        else { abrirDetalhes(c.item) }
    }

    func favoritar(_ i: Item) {
        let on = Store.s.toggleFav(i.key)
        enviarSincronia()
        if i.kind == .channel { toast(on ? "\(i.name) adicionado aos favoritos" : "\(i.name) removido dos favoritos") }
        else { toast(on ? "Adicionado à Minha lista" : "Removido da Minha lista") }
    }

    // ---------- Atualização ----------

    func procurarAtualizacao(manual: Bool) async {
        ultimaAtualizacao = agora
        if manual { statusAtualizacao = "Procurando atualizações..." }
        do {
            guard let v = try await Atualizador.procurar() else {
                novaVersao = nil
                statusAtualizacao = "Você está na versão mais recente (\(Atualizador.rotulo(Atualizador.atual)))."
                return
            }
            let nova = novaVersao?.versao != v.versao
            novaVersao = v
            statusAtualizacao = "A versão \(Atualizador.rotulo(v.versao)) está disponível."
            if !manual && nova { bannerAtualizacao = true }
            if nova { avisar("StreamPLAY \(Atualizador.rotulo(v.versao)) disponível: toque em Configurações para atualizar.") }
        } catch {
            if manual { statusAtualizacao = "Não foi possível procurar atualizações agora. Confira sua internet." }
        }
    }

    /// No iPhone: abre a página da versão nova (o .ipa é instalado de novo pelo AltStore/Sideloadly).
    func instalarAtualizacao() {
        bannerAtualizacao = false
        if let u = URL(string: Atualizador.pagina) { UIApplication.shared.open(u) }
    }

    // ---------- Aviso ----------

    func toast(_ texto: String, fixo: Bool = false) {
        nAviso += 1
        aviso = Aviso(texto: texto, fixo: fixo, n: nAviso)
    }
}
