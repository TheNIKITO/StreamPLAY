package com.thenikito.streamplay

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed class Tela {
    data object Abertura : Tela()
    data class Login(val aviso: String?) : Tela()
    data class Bloqueio(val r: Conta.Resultado) : Tela()
    // classe comum (não data): o mesmo erro duas vezes seguidas ainda conta como uma tela nova
    class Fonte(val erro: String?) : Tela()
    data object Principal : Tela()
}

class Aviso(val texto: String, val fixo: Boolean)

/** Estado do app e as regras da janela principal do PC (MainWindow.xaml.cs): conta, lista, navegação e player. */
object Estado {
    val scope = MainScope()

    @SuppressLint("StaticFieldLeak") // é o contexto do aplicativo, não de uma tela
    private lateinit var app: Context

    var tela by mutableStateOf<Tela>(Tela.Abertura)
    var abertura by mutableStateOf(true)
    var textoAbertura by mutableStateOf("")
    /** Barra da lista carregando (0 a 1) na abertura e em "Escolha sua lista"; -1 = sem barra. */
    var andamento by mutableFloatStateOf(-1f)
    var lib by mutableStateOf<Library?>(null)
    var tab by mutableStateOf("home")
    var detalhes by mutableStateOf<Item?>(null)
    var temporada by mutableIntStateOf(0)
    var ajustes by mutableStateOf(false)
    var player by mutableStateOf<PlayRequest?>(null)
    var busca by mutableStateOf("")
    /** Muda quando a aba deve voltar ao começo (saiu de um título aberto pela busca): a página fecha o "ver tudo". */
    var voltarAoInicio by mutableIntStateOf(0)
    var aviso by mutableStateOf<Aviso?>(null)
    var contaAtual by mutableStateOf<Conta.Resultado?>(null)

    /** Avisos do sino (texto, quando), o mais novo primeiro; avisosNovos acende a bolinha vermelha. */
    val avisos = mutableStateListOf<Pair<String, String>>()
    var avisosNovos by mutableStateOf(false)

    fun avisar(texto: String) {
        if (texto.isBlank() || avisos.firstOrNull()?.first == texto) return
        val quando = java.text.SimpleDateFormat("dd/MM 'às' HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date())
        avisos.add(0, texto to quando)
        while (avisos.size > 15) avisos.removeAt(avisos.size - 1)
        avisosNovos = true
    }
    var topoSolido by mutableFloatStateOf(0f)

    var novaVersao by mutableStateOf<Atualizador.Versao?>(null)
    var bannerAtualizacao by mutableStateOf(false)
    var statusAtualizacao by mutableStateOf("")
    var baixando by mutableStateOf(false)

    /** Na primeira vez que a tela principal aparece (na TV), o foco vai para o primeiro botão. */
    var focoInicialPendente = true

    /** Último elemento focado (pelo controle remoto) e onde o foco volta quando uma sobreposição fecha. */
    var ultimoFoco: FocusRequester? = null

    /** Para onde o foco vai ao apertar para baixo na barra de cima (o conteúdo começa por baixo dela). */
    var alvoConteudo: FocusRequester? = null
    private var focoAntes: FocusRequester? = null

    private var iniciado = false
    private var carregandoLista = false
    private var checandoLista = false
    private var verificandoConta = false
    private var pendente: Library? = null
    private var ultimaConta = 0L
    private var ultimaLista = 0L
    private var ultimaAtualizacao = 0L
    private var ultimoGuia = 0L
    private var estaNaFrente = false
    private var onlineId: String? = null   // conta que está aparecendo como online no painel
    private var ultimoSinal = 0L
    private var ultimoEnvio = 0L

    private const val MIN = 60_000L
    private const val ONLINE_CADA = 5 * MIN
    private const val CONTA_CADA = 15 * MIN
    private const val LISTA_CADA = 10 * MIN
    private const val ATUALIZA_CADA = 30 * MIN
    // a grade anda e o arquivo do guia é renovado a cada 6 h: relê de tempos em tempos (como o PC)
    private const val GUIA_CADA = 30 * MIN

    // sem internet, quem já entrou continua usando por até 3 dias desde a última confirmação
    private const val TOLERANCIA = 3 * 24 * 60 * MIN

    private val agora get() = System.currentTimeMillis()
    val naTelaDeConta get() = tela is Tela.Login || tela is Tela.Bloqueio

    fun iniciar(ctx: Context) {
        if (iniciado) return
        iniciado = true
        app = ctx.applicationContext
        tab = Store.tab
        Conta.definirAparelho(com.thenikito.streamplay.ui.ehTv(ctx), android.os.Build.MANUFACTURER, android.os.Build.MODEL)
        ultimaConta = agora
        ultimaLista = agora
        ultimaAtualizacao = agora
        scope.launch {
            val inicio = SystemClock.elapsedRealtime()
            if (garantirConta()) carregarLista()
            val espera = 1150 - (SystemClock.elapsedRealtime() - inicio)
            if (espera > 0) delay(espera)
            abertura = false
            if (!naTelaDeConta) procurarAtualizacao(manual = false)
        }
    }

    /** Ao voltar para o app: confere conta, lista e atualização se já faz um tempo. */
    suspend fun aoVoltar() {
        if (!iniciado) return
        if (agora - ultimaConta > 10 * MIN) conferirConta()
        if (agora - ultimaLista > 3 * MIN) conferirLista()
        if (agora - ultimaAtualizacao > ATUALIZA_CADA) procurarAtualizacao(manual = false)
    }

    /** O app foi para a frente (true) ou saiu dela (false): liga ou desliga o "online" no painel. */
    fun naFrente(sim: Boolean) {
        estaNaFrente = sim
        atualizarOnline()
    }

    // Aparece como online no painel enquanto o app está na frente com a conta liberada; renova a cada 5 min.
    private fun atualizarOnline() {
        val id = if (estaNaFrente && contaAtual != null) Store.acessoId else null
        if (id == onlineId) {
            if (id != null && agora - ultimoSinal >= ONLINE_CADA) {
                sinal(id, true)
                // junto com o sinal: parado nas telas, confere o que os outros aparelhos assistiram; vendo algo,
                // manda o progresso a cada 10 min (ao pausar e ao sair do player ele já vai na hora)
                if (player == null) baixarSincronia(id) else if (agora - ultimoEnvio >= 10 * MIN) enviarSincronia()
            }
            return
        }
        onlineId?.let { antigo -> sinal(antigo, false); enviarSincronia(antigo) }   // saiu da frente: manda o que mudou
        onlineId = id
        if (id != null) {
            sinal(id, true)
            if (Sincronia.sujo) enviarSincronia() else baixarSincronia(id)
        }
    }

    // ---------- Sincronização (continuar assistindo e Minha lista entre os aparelhos da conta) ----------

    private fun baixarSincronia(id: String) {
        scope.launch {
            if (Sincronia.baixar(id)) Store.versao.intValue++   // as telas releem progresso e favoritos
            if (Sincronia.sujo) enviarSincronia(id)
        }
    }

    /** Manda progresso e Minha lista para a nuvem (se mudou algo). Ao pausar, ao sair do player, ao favoritar. */
    fun enviarSincronia(id: String? = onlineId) {
        if (id == null || !Sincronia.sujo) return
        ultimoEnvio = agora
        scope.launch { if (Sincronia.enviar(id)) Store.versao.intValue++ }
    }

    private fun sinal(id: String, online: Boolean) {
        if (online) ultimoSinal = agora
        scope.launch(Dispatchers.IO) { Conta.sinal(id, online) }
    }

    suspend fun periodico() {
        if (!iniciado) return
        atualizarOnline()
        if (agora - ultimaConta >= CONTA_CADA) conferirConta()
        if (agora - ultimaLista >= LISTA_CADA) conferirLista()
        if (agora - ultimoGuia >= GUIA_CADA) carregarGuia()
        if (agora - ultimaAtualizacao >= ATUALIZA_CADA) procurarAtualizacao(manual = false)
    }

    // ---------- Lista ----------

    suspend fun carregarLista(quiet: Boolean = false) {
        val fonte = Store.source
        if (fonte.isNullOrBlank()) { mostrarFonte(null); return }
        if (carregandoLista) return
        carregandoLista = true
        if (!quiet) { textoAbertura = ""; andamento = 0f }
        try {
            val nova = Fonte.carregar(Pastas.de(app), fonte) { if (!quiet) andamento = it }
            Store.listaSalva = fonte
            Store.save()
            definirLista(nova)
            if (quiet) { toast("Lista atualizada  •  " + nova.summary); avisar("Lista atualizada  •  " + nova.summary) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val msg = Fonte.mensagemErro(e)
            val salva = if (quiet) null else try { if (Store.listaSalva == fonte) Fonte.carregarSalva(Pastas.de(app), fonte) else null } catch (_: Throwable) { null }
            when {
                salva != null -> {
                    definirLista(salva)
                    toast("Não consegui baixar a lista agora ($msg). Mostrando a última cópia salva.")
                }
                quiet && lib != null -> toast("Não consegui recarregar a lista: $msg")
                else -> mostrarFonte("Não foi possível abrir a lista: $msg")
            }
        } finally {
            carregandoLista = false
            ultimaLista = agora
        }
    }

    private fun definirLista(nova: Library) {
        lib = nova
        carregarGuia()
        pendente = null
        focoInicialPendente = true
        if (naTelaDeConta) return   // lista recarregou por trás da tela de login: só aparece depois de entrar
        tela = Tela.Principal
    }

    /** Lista online (ou arquivo do aparelho): confere novidades a cada 10 min e ao voltar para o app. */
    private suspend fun conferirLista() {
        val fonte = Store.source
        if (fonte.isNullOrBlank() || checandoLista || carregandoLista || lib == null) return
        checandoLista = true
        ultimaLista = agora
        try {
            val nova = Fonte.carregarSeMudou(Pastas.de(app), fonte)
            if (nova != null && fonte == Store.source) aplicarNova(nova)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        } finally {
            checandoLista = false
        }
    }

    private fun aplicarNova(nova: Library) {
        // não troca a tela embaixo de quem está com os detalhes abertos, buscando ou assistindo: aplica depois
        if (detalhes != null || busca.isNotEmpty() || player != null) { pendente = nova; return }
        definirLista(nova)
        toast("Lista atualizada  •  " + nova.summary)
        avisar("Lista atualizada  •  " + nova.summary)
    }

    /** Guia de programação dos canais (em segundo plano). */
    private fun carregarGuia() {
        val l = lib ?: return
        ultimoGuia = agora
        scope.launch { Guia.carregar(Pastas.de(app), l) }
    }

    private fun aplicarPendente() {
        pendente?.let { aplicarNova(it) }
    }

    fun mostrarFonte(erro: String?) {
        if (naTelaDeConta) return
        detalhes = null
        ajustes = false
        tela = Tela.Fonte(erro)
    }

    fun sairDaFonte() {
        if (lib == null || naTelaDeConta) return
        tela = Tela.Principal
    }

    fun usarFonte(fonte: String) {
        andamento = 0f
        Store.source = fonte
        Store.save()
        scope.launch { carregarLista() }
    }

    fun recarregar() {
        ajustes = false
        scope.launch { carregarLista(quiet = true) }
    }

    // ---------- Conta (PLAYGER) ----------

    /** Na abertura: confere a conta salva. Devolve false quando a tela de login ficou na frente. */
    private suspend fun garantirConta(): Boolean {
        val id = Store.acessoId
        if (id.isNullOrEmpty()) { mostrarLogin(Tela.Login(null)); return false }
        textoAbertura = "Verificando seu acesso..."
        val r = Conta.verificar(id)
        if (r.estado == Conta.Estado.Liberado) { aceitarConta(r); avisarVencimento(r); return true }
        if (r.estado == Conta.Estado.SemConexao && agora - Store.contaOk < TOLERANCIA) {
            ultimaConta = agora
            toast("Sem internet para confirmar seu acesso agora. Vou tentar de novo daqui a pouco.")
            return true
        }
        mostrarConta(r)
        return false
    }

    /** Conferência periódica (e ao voltar para o app): bloqueia na hora se o acesso caiu. */
    private suspend fun conferirConta() {
        val id = Store.acessoId
        if (verificandoConta || id.isNullOrEmpty() || naTelaDeConta || (contaAtual == null && Store.contaOk == 0L)) return
        verificandoConta = true
        ultimaConta = agora
        try {
            val r = Conta.verificar(id)
            if (naTelaDeConta) return
            if (r.estado == Conta.Estado.Liberado) { if (aceitarConta(r)) carregarLista(quiet = true) }
            else if (r.estado != Conta.Estado.SemConexao || agora - Store.contaOk > TOLERANCIA) mostrarConta(r)
        } finally {
            verificandoConta = false
        }
    }

    /** Guarda a conta liberada. Devolve true quando o painel mandou outra lista (aí a lista precisa ser recarregada). */
    private fun aceitarConta(r: Conta.Resultado): Boolean {
        contaAtual = r
        Store.contaOk = agora
        r.acesso?.login?.takeIf { it.isNotEmpty() }?.let { Store.login = it }
        // a lista vem do painel (PLAYGER) junto com o login: o cliente não digita nem vê o link
        val lista = r.acesso?.lista?.trim()
        val mudou = !lista.isNullOrEmpty() && Fonte.isUrl(lista) && lista != Store.source
        if (mudou) Store.source = lista
        Store.save()
        ultimaConta = agora
        atualizarOnline()
        return mudou
    }

    /** A lista é a que o painel manda com o login (o botão "Trocar lista" some). */
    val listaDoPainel get() = !contaAtual?.acesso?.lista.isNullOrBlank()

    private fun avisarVencimento(r: Conta.Resultado) {
        Conta.aviso(r)?.let { toast(it); avisar(it) }
    }

    private fun mostrarConta(r: Conta.Resultado) {
        when (r.estado) {
            Conta.Estado.Invalido -> sairDaConta("Sua senha mudou ou o acesso foi removido. Entre de novo.")
            Conta.Estado.Bloqueado -> mostrarLogin(Tela.Bloqueio(r))
            else -> mostrarLogin(Tela.Login(r.mensagem))
        }
    }

    private fun mostrarLogin(t: Tela) {
        contaAtual = null
        atualizarOnline()
        busca = ""
        detalhes = null
        ajustes = false
        player = null
        tela = t
    }

    /** A tela de login confirmou a conta (login certo ou "já paguei"). */
    fun contaLiberada(r: Conta.Resultado) {
        val outraLista = aceitarConta(r)
        if (lib != null && !outraLista) {
            tela = Tela.Principal
            focoInicialPendente = true
        } else {
            tela = Tela.Abertura
            abertura = true
            scope.launch {
                carregarLista()
                abertura = false
            }
        }
        avisarVencimento(r)
        scope.launch { procurarAtualizacao(manual = false) }
    }

    /** Login certo mas acesso bloqueado: fica na tela de bloqueio (com "verificar de novo"). */
    fun contaBloqueada(r: Conta.Resultado) {
        tela = Tela.Bloqueio(r)
    }

    fun sairDaConta(aviso: String?) {
        Store.acessoId = null
        Store.contaOk = 0
        Store.save()
        mostrarLogin(Tela.Login(aviso))
    }

    // ---------- Navegação ----------

    fun navegar(t: String) {
        if (lib == null || naTelaDeConta) return
        tab = t
        Store.tab = t
        busca = ""
        topoSolido = 0f
    }

    fun limparBusca() {
        busca = ""
        aplicarPendente()
    }

    private val semSobreposicao get() = detalhes == null && !ajustes && player == null

    fun abrirDetalhes(i: Item) {
        if (semSobreposicao) focoAntes = ultimoFoco
        temporada = 0
        detalhes = i
    }

    fun fecharDetalhes() {
        detalhes = null
        // saiu de um título aberto pela busca: a aba volta para o começo (o cartão da busca some, o foco vai para o destaque)
        if (busca.isNotEmpty()) {
            busca = ""
            voltarAoInicio++
            focoAntes = null
            focoInicialPendente = true
        }
        aplicarPendente()
    }

    fun abrirAjustes() {
        if (semSobreposicao) focoAntes = ultimoFoco
        ajustes = true
    }

    fun fecharAjustes() {
        ajustes = false
    }

    /** Depois que uma sobreposição fecha, o foco do controle remoto volta para onde estava. */
    fun voltarFoco() {
        val f = focoAntes ?: return
        focoAntes = null
        try { f.requestFocus() } catch (_: Exception) { }
    }

    // ---------- Player ----------

    fun tocar(req: PlayRequest?) {
        if (req == null || req.url.isNullOrBlank()) { toast("Este item não tem link de vídeo."); return }
        if (naTelaDeConta) return
        if (semSobreposicao) focoAntes = ultimoFoco
        player = req
    }

    fun fecharPlayer() {
        player = null
        Store.save()
        enviarSincronia()   // saiu do player: o outro aparelho já pode continuar daqui
        aplicarPendente()
    }

    fun abrirCartao(c: Cartao) {
        when {
            c.ep != null -> tocar(PlayRequest.forEpisode(c.ep))
            c.direct -> tocar(PlayRequest.forItem(c.item))
            else -> abrirDetalhes(c.item)
        }
    }

    // ---------- Atualização automática ----------

    suspend fun procurarAtualizacao(manual: Boolean) {
        ultimaAtualizacao = agora
        if (baixando) return
        if (manual) statusAtualizacao = "Procurando atualizações..."
        try {
            val v = Atualizador.procurar()
            if (v == null) {
                novaVersao = null
                statusAtualizacao = "Você está na versão mais recente (${Atualizador.atual})."
                return
            }
            val nova = novaVersao?.versao != v.versao
            novaVersao = v
            statusAtualizacao = "A versão ${v.versao} está disponível."
            if (!manual && nova) bannerAtualizacao = true
            if (nova) avisar("StreamPLAY ${v.versao} disponível: toque em Configurações para atualizar.")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            if (manual) statusAtualizacao = "Não foi possível procurar atualizações agora. Confira sua internet."
        }
    }

    fun instalarAtualizacao(act: Activity) {
        val v = novaVersao ?: return
        if (baixando) return
        baixando = true
        bannerAtualizacao = false
        scope.launch {
            try {
                statusAtualizacao = "Baixando a versão ${v.versao}..."
                val f = Atualizador.baixar(act.cacheDir, v) { p -> statusAtualizacao = "Baixando a versão ${v.versao}...  ${(p * 100).toInt()}%" }
                statusAtualizacao = "A versão ${v.versao} está pronta para instalar."
                if (!Atualizador.instalar(act, f))
                    toast("Permita que o StreamPLAY instale atualizações e toque em Atualizar de novo.", fixo = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                statusAtualizacao = "Não consegui baixar a atualização (${Fonte.mensagemErro(e)}). Tente de novo."
                toast(statusAtualizacao)
            } finally {
                baixando = false
            }
        }
    }

    // ---------- Aviso ----------

    fun toast(texto: String, fixo: Boolean = false) {
        aviso = Aviso(texto, fixo)
    }
}
