package com.thenikito.streamplay

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.CancellationException
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
    var lib by mutableStateOf<Library?>(null)
    var tab by mutableStateOf("movies")
    var detalhes by mutableStateOf<Item?>(null)
    var temporada by mutableIntStateOf(0)
    var ajustes by mutableStateOf(false)
    var player by mutableStateOf<PlayRequest?>(null)
    var busca by mutableStateOf("")
    var aviso by mutableStateOf<Aviso?>(null)
    var contaAtual by mutableStateOf<Conta.Resultado?>(null)
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

    private const val MIN = 60_000L
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

    suspend fun periodico() {
        if (!iniciado) return
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
        if (!quiet) textoAbertura = "Carregando sua lista..."
        try {
            val nova = Fonte.carregar(Pastas.de(app), fonte) { if (!quiet) textoAbertura = it }
            Store.listaSalva = fonte
            Store.save()
            definirLista(nova)
            if (quiet) toast("Lista atualizada  •  " + nova.summary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val msg = Fonte.mensagemErro(e)
            val salva = if (quiet) null else try { if (Store.listaSalva == fonte) Fonte.carregarSalva(Pastas.de(app), fonte) { textoAbertura = it } else null } catch (_: Throwable) { null }
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
        textoAbertura = "Carregando sua lista..."
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
            if (r.estado == Conta.Estado.Liberado) aceitarConta(r)
            else if (r.estado != Conta.Estado.SemConexao || agora - Store.contaOk > TOLERANCIA) mostrarConta(r)
        } finally {
            verificandoConta = false
        }
    }

    private fun aceitarConta(r: Conta.Resultado) {
        contaAtual = r
        Store.contaOk = agora
        r.acesso?.login?.takeIf { it.isNotEmpty() }?.let { Store.login = it }
        Store.save()
        ultimaConta = agora
    }

    private fun avisarVencimento(r: Conta.Resultado) {
        Conta.aviso(r)?.let { toast(it) }
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
        busca = ""
        detalhes = null
        ajustes = false
        player = null
        tela = t
    }

    /** A tela de login confirmou a conta (login certo ou "já paguei"). */
    fun contaLiberada(r: Conta.Resultado) {
        aceitarConta(r)
        if (lib != null) {
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
