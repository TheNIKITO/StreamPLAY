package com.thenikito.streamplay

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.RendererItem
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.util.Locale

class Erro(val titulo: String, val texto: String)
class Osd(val texto: String)
class Flash(val tipo: String)

/** O player (VLC) com as mesmas regras do PlayerView do PC: retomar, próximo episódio, troca de canal e a vigia. */
class PlayerControle(primeiro: PlayRequest, private val app: Context) {
    var req by mutableStateOf(primeiro); private set
    var tocando by mutableStateOf(false); private set
    var ocupado by mutableStateOf(true); private set
    var tempo by mutableLongStateOf(0L); private set
    var duracao by mutableLongStateOf(0L); private set
    var erro by mutableStateOf<Erro?>(null); private set
    var proximo by mutableStateOf<Episode?>(null); private set
    var cartaoProximo by mutableStateOf(false); private set
    var fracProximo by mutableFloatStateOf(0f); private set
    var retomar by mutableStateOf<String?>(null); private set
    var osd by mutableStateOf<Osd?>(null); private set
    var flash by mutableStateOf<Flash?>(null); private set
    var controles by mutableStateOf(true); private set
    var painel by mutableStateOf<String?>(null); private set
    var pausadoInfo by mutableStateOf(false); private set
    var aspecto by mutableStateOf("original"); private set
    var velocidade by mutableFloatStateOf(1f); private set
    var faixas by mutableIntStateOf(0); private set
    var arrastando by mutableStateOf(false); private set
    var posArrasto by mutableFloatStateOf(0f); private set
    /** Nome do Chromecast para onde o vídeo está indo (null = tocando no próprio aparelho). */
    var transmitindoPara by mutableStateOf<String?>(null); private set
    private var cast: RendererItem? = null
    val transmitindo get() = cast != null

    /** A tela observa: quando muda, coloca o foco do controle remoto em [alvoFoco] ("seek", "play" ou "tentar"). */
    var pedidoFoco by mutableIntStateOf(0); private set
    var alvoFoco = "play"; private set

    /** O foco está no fundo do player (controles escondidos ou recém-mostrados): as setas viram atalhos. */
    var raizFocada = true

    private var mp: MediaPlayer? = null
    private var layout: VLCVideoLayout? = null
    private var anexado = false
    private var iniciado = false
    private var fechado = false
    private var recomecarAoVoltar = false
    private val main = Handler(Looper.getMainLooper())

    private var cache = 0f
    private var lastTime = -1L
    private var retries = 0
    private var startedAt = 0L
    private var playingAt = 0L
    private var vigiaEspera = 0L
    private var vigiado = false
    private var simulado = false
    private var reabrirEm = -1L
    private var vouts = 0
    private var creditsDismissed = false
    private var ultimoToque = agora()
    private var lastSave = agora()
    private var pausadoEm = 0L
    private var retomarEm = 0L
    private var proxEm = 0L

    companion object {
        /** Links em que o vídeo não apareceu com aceleração: abrem direto sem ela. */
        private val semAceleracao = HashSet<String>()
        private fun agora() = SystemClock.elapsedRealtime()
        private val PT = Regex("portugu|\\bpt\\b|\\bpor\\b|dublad", RegexOption.IGNORE_CASE)
    }

    val ehAoVivo get() = req.isLive
    val variosCanais get() = req.isLive && (req.channels?.size ?: 0) > 1
    val temEpisodios get() = req.episode != null && req.item.episodes.size > 1

    // ---------- Início ----------

    suspend fun preparar() {
        val lib = Vlc.pronto()
        if (fechado) return
        mp = MediaPlayer(lib).also { it.setEventListener { e -> evento(e.type, e.buffering, e.voutCount) } }
        // estava transmitindo para a TV: o próximo título também vai para lá (até a pessoa escolher "Este celular")
        Transmissao.atual?.let { a -> if (mp?.setRenderer(a) == 0) ligarTransmissao(a) }
        talvezComecar()
    }

    fun falhaMotor(e: Throwable) {
        erro = Erro("Não foi possível iniciar o player", "O motor do VLC não abriu neste aparelho (${e.message ?: e.javaClass.simpleName}).")
        ocupado = false
        pedirFoco("tentar")
    }

    fun anexar(l: VLCVideoLayout) {
        layout = l
        talvezComecar()
    }

    private fun talvezComecar() {
        val mp = mp ?: return
        val l = layout ?: return
        if (fechado) return
        if (!anexado) { mp.attachViews(l, null, true, false); anexado = true }
        if (!iniciado) { iniciado = true; start(req) }
    }

    private fun start(r: PlayRequest) {
        val mp = mp ?: return
        req = r
        creditsDismissed = false
        cartaoProximo = false
        erro = null
        cache = 0f
        lastTime = -1
        startedAt = agora()
        playingAt = 0
        vigiaEspera = 0
        vigiado = false
        vouts = 0
        simulado = reabrirEm >= 0
        retomar = null
        pausadoInfo = false
        painel = null
        proximo = if (r.episode != null) Resume.next(r.episode) else null
        tempo = 0
        duracao = 0
        ocupado = true

        val media = criarMedia(r)
        mp.setMedia(media)
        media.release()
        mp.play()
        if (velocidade != 1f) mp.setRate(velocidade)
        aplicarAspecto()
        if (r.isLive) Store.addRecent(r.item.key)
        mostrarControles()
    }

    private fun criarMedia(r: PlayRequest): Media {
        val lib = Vlc.lib!!
        val url = r.url!!.trim()
        val media = if (url.contains("://")) Media(lib, Uri.parse(url)) else Media(lib, url)
        // aceleração de vídeo do aparelho, voltando para o processador se precisar
        media.setHWDecoderEnabled(!semAceleracao.contains(url), false)
        media.addOption(":network-caching=${Store.caching}")
        if (r.isLive) {
            media.addOption(":http-reconnect")
            media.addOption(":live-caching=${Store.caching}")
        }
        for (o in r.options) if (o.isNotBlank()) media.addOption(":" + o.trim().trimStart(':'))

        if (reabrirEm >= 0) {
            if (!r.isLive) media.addOption(":start-time=" + segundos(reabrirEm))
            reabrirEm = -1
        } else if (!r.isLive && !r.fromStart) {
            val p = Store.getProgress(r.key)
            if (p != null && !p.finished && p.pos > 15000) {
                val at = maxOf(0L, p.pos - 3000)
                media.addOption(":start-time=" + segundos(at))
                retomar = "Continuando de ${tempo(at)}"
                retomarEm = agora()
            }
        }
        r.fromStart = false
        return media
    }

    private fun segundos(ms: Long) = String.format(Locale.US, "%.1f", ms / 1000.0)

    // ---------- Eventos do VLC ----------

    private fun evento(tipo: Int, buffering: Float, vouts: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { evento(tipo, buffering, vouts) }; return }
        if (fechado || mp == null) return
        when (tipo) {
            MediaPlayer.Event.Playing -> onPlaying()
            MediaPlayer.Event.Paused -> { pausadoEm = agora(); tocando = false }
            MediaPlayer.Event.Buffering -> cache = buffering
            MediaPlayer.Event.EndReached -> onEnded()
            MediaPlayer.Event.EncounteredError -> onError()
            MediaPlayer.Event.Vout -> this.vouts = vouts
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> faixas++
        }
    }

    private fun onPlaying() {
        if (playingAt == 0L) playingAt = agora()
        tocando = true
        pausadoInfo = false
    }

    private fun onEnded() {
        val mp = mp ?: return
        if (req.isLive) {
            if (retries++ < 3) { retry(1200); return }
            mostrarErro("A transmissão foi interrompida", "O canal parou de enviar vídeo. Tente de novo em alguns instantes.")
            return
        }
        val len = mp.length
        if (len > 0) Store.setProgress(req.key, len, len, req.showKey)
        if (proximo != null && Store.autoNext && !creditsDismissed) { playNext(); return }
        fechar()
    }

    private fun onError() {
        if (req.isLive && retries++ < 2) { retry(1500); return }
        if (transmitindo) {
            mostrarErro(
                "Não foi possível transmitir para $transmitindoPara",
                "Confira se a TV está ligada e na mesma rede Wi-Fi deste celular, e tente de novo.",
            )
            return
        }
        val url = req.url ?: ""
        val host = try { if (url.contains("://")) Uri.parse(url).host ?: "" else File(url).name } catch (_: Exception) { "" }
        mostrarErro(
            "Não foi possível reproduzir",
            "O VLC não conseguiu abrir este vídeo. Confira se o link da sua lista está funcionando" + (if (host.isNotEmpty()) " ($host)." else "."),
        )
    }

    private fun retry(espera: Long) {
        ocupado = true
        val r = req
        main.postDelayed({ if (!fechado && mp != null && req === r) start(r) }, espera)
    }

    private fun mostrarErro(titulo: String, texto: String) {
        erro = Erro(titulo, texto)
        ocupado = false
        controles = true
        pedirFoco("tentar")
    }

    fun tentarDeNovo() {
        retries = 0
        if (mp == null) { erro = null; return }
        start(req)
    }

    // ---------- Laço (4 vezes por segundo) ----------

    fun tick() {
        val mp = mp ?: return
        if (fechado) return
        val now = agora()
        val st = mp.playerState   // 0 nada, 1 abrindo, 2 carregando, 3 tocando, 4 pausado, 5 parado, 6 fim, 7 erro
        val len = mp.length
        val t = mp.time
        val playing = st == 3

        if (playing) vigiar(now, t)
        if (playing && t > 0 && lastTime >= 0 && t != lastTime) cache = 100f
        lastTime = t
        if (playing && now - startedAt > 20_000) retries = 0
        tocando = st == 3 || st == 1 || st == 2 || st == 0
        ocupado = erro == null && (st == 0 || st == 1 || st == 2 || (playing && cache < 100f))

        if (!req.isLive) {
            if (len > 0) { duracao = len; tempo = t }
            if (len > 0 && playing && now - lastSave > 5000) salvarProgresso()
            if (proximo != null && !cartaoProximo && !creditsDismissed && len > 180_000 && t > 0 && len - t < 25_000) {
                cartaoProximo = true
                proxEm = now
            }
            if (cartaoProximo && Store.autoNext) {
                val f = (now - proxEm) / 10_000f
                fracProximo = minOf(1f, f)
                if (f >= 1f) { playNext(); return }
            }
        }

        if (retomar != null && now - retomarEm > 7000) retomar = null
        // transmitindo, os controles ficam à vista (a tela do celular vira o controle remoto da TV)
        if (controles && playing && painel == null && !arrastando && erro == null && !transmitindo && now - ultimoToque > 3200) esconderControles()
        if (st == 4 && !pausadoInfo && painel == null && erro == null && !transmitindo && now - pausadoEm > 6000 && now - ultimoToque > 6000 && !req.isLive) {
            pausadoInfo = true
            esconderControles()
        }
    }

    // ---------- Vigia dos primeiros segundos ----------
    // Alguns arquivos abrem sem faixa de áudio escolhida, ou com o vídeo sem aparecer (tela preta com som).
    // Nos primeiros segundos o player confere e corrige: escolhe o áudio (de preferência em português) e, se o
    // vídeo não aparecer, reabre no mesmo ponto sem aceleração de vídeo — e lembra disso para esse link.

    private fun vigiar(now: Long, t: Long) {
        val mp = mp ?: return
        // na TV não há imagem aqui: a vigia acharia que o vídeo "não apareceu" e reabriria à toa
        if (vigiado || playingAt == 0L || transmitindo) return
        val s = (now - playingAt) / 1000.0
        if (s < 1.5 || now < vigiaEspera) return
        if (t > 8000 || s > 40) vigiado = true
        // só mexe nas faixas depois que o vídeo começou a andar (antes disso elas ainda podem estar carregando)
        val andando = t > 1500 || s > 12
        try {
            if (andando && mp.audioTrack == -1 && mp.audioTracksCount > 0) {
                val a = melhorAudio()
                if (a != null) {
                    mp.setAudioTrack(a)
                    vigiaEspera = now + 3000
                    vigiado = false
                    return
                }
            }
            if (mp.videoTracksCount > 0) {
                if (mp.videoTrack == -1) {
                    val v = if (andando) mp.videoTracks?.firstOrNull { it.id != -1 } else null
                    if (v != null) {
                        mp.setVideoTrack(v.id)
                        vigiaEspera = now + 3000
                        vigiado = false
                        return
                    }
                } else if (vouts == 0 && s > 3 && t > 1500 && semAceleracao.add(req.url!!.trim())) {
                    // tem vídeo e o tempo está andando, mas nada aparece na tela
                    vigiado = true
                    reabrirEm = maxOf(0L, t - 1000)
                    start(req)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun melhorAudio(): Int? {
        val l = (mp?.audioTracks ?: return null).filter { it.id != -1 }
        if (l.isEmpty()) return null
        return (l.firstOrNull { PT.containsMatchIn(it.name ?: "") } ?: l[0]).id
    }

    fun salvarProgresso() {
        val mp = mp ?: return
        if (req.isLive) return
        val len = mp.length
        val t = mp.time
        lastSave = agora()
        if (len <= 0 || t < 0) return
        if (t < 10_000 && Store.getProgress(req.key) == null) return
        Store.setProgress(req.key, t, len, req.showKey)
    }

    // ---------- Controles ----------

    fun poke() {
        ultimoToque = agora()
        if (pausadoInfo) pausadoInfo = false
        if (!controles) mostrarControles()
    }

    fun mostrarControles() {
        controles = true
        ultimoToque = agora()
    }

    fun esconderControles() {
        controles = false
        painel = null
    }

    fun alternarControles() {
        if (painel != null) { painel = null; return }
        if (controles) esconderControles() else poke()
    }

    fun pedirFoco(alvo: String) {
        alvoFoco = alvo
        pedidoFoco++
    }

    fun abrirPainel(nome: String) {
        painel = if (painel == nome) null else nome
        controles = true
        ultimoToque = agora()
    }

    fun fecharPainel() {
        painel = null
        ultimoToque = agora()
        pedirFoco("play")
    }

    // ---------- Reprodução ----------

    fun togglePlay() {
        val mp = mp ?: return
        val st = mp.playerState
        if (st == 5 || st == 6 || st == 7) { start(req); return }
        if (mp.isPlaying) {
            mp.pause()
            pausadoEm = agora()
            salvarProgresso()
            Estado.enviarSincronia()   // pausou: o outro aparelho já pode continuar daqui
            tocando = false
            flash = Flash("pause")
        } else {
            mp.play()
            tocando = true
            flash = Flash("play")
            pausadoInfo = false
        }
        poke()
    }

    fun skip(ms: Long) {
        val mp = mp ?: return
        if (req.isLive || !mp.isSeekable) return
        val len = mp.length
        val t = (mp.time + ms).coerceIn(0L, if (len > 0) len - 1500 else Long.MAX_VALUE)
        mp.setTime(t)
        tempo = t
        flash = Flash(if (ms < 0) "rew" else "fwd")
        poke()
    }

    fun irPara(fracao: Float) {
        val mp = mp ?: return
        if (req.isLive || duracao <= 0 || !mp.isSeekable) return
        val t = (fracao.coerceIn(0f, 1f) * duracao).toLong()
        mp.setTime(t)
        tempo = t
        poke()
    }

    fun arrastar(fracao: Float) {
        arrastando = true
        posArrasto = fracao.coerceIn(0f, 1f)
        ultimoToque = agora()
    }

    fun soltar() {
        if (!arrastando) return
        arrastando = false
        irPara(posArrasto)
    }

    fun doInicio() {
        mp?.setTime(0)
        tempo = 0
        retomar = null
        poke()
    }

    fun playNext() {
        val n = proximo ?: return
        salvarProgresso()
        val mp = mp
        if (mp != null && mp.length > 0 && mp.length - mp.time < 60_000) Store.setProgress(req.key, mp.length, mp.length, req.showKey)
        start(PlayRequest.forEpisode(n)!!)
    }

    fun dispensarCreditos() {
        creditsDismissed = true
        cartaoProximo = false
    }

    fun trocar(r: PlayRequest) {
        if (fechado || mp == null) return
        salvarProgresso()
        retries = 0
        start(r)
        pedirFoco("play")
    }

    fun zap(dir: Int) {
        val list = req.channels ?: return
        if (list.size < 2) return
        val i = list.indexOf(req.item)
        val next = list[((if (i < 0) 0 else i) + dir + list.size) % list.size]
        retries = 0
        start(PlayRequest.forChannel(next, list))
        mostrarOsd(next.name)
    }

    fun mostrarOsd(texto: String) {
        osd = Osd(texto)
    }

    // ---------- Áudio, legendas, velocidade e imagem ----------

    fun faixasAudio(): List<Pair<Int, String>> =
        (mp?.audioTracks ?: emptyArray()).filter { it.id != -1 }.mapIndexed { i, t -> t.id to nomeFaixa(t.name, i + 1, false) }

    fun audioAtual() = mp?.audioTrack ?: -1

    fun escolherAudio(id: Int) {
        mp?.setAudioTrack(id)
        faixas++
    }

    fun faixasLegenda(): List<Pair<Int, String>> =
        (mp?.spuTracks ?: emptyArray()).filter { it.id != -1 }.mapIndexed { i, t -> t.id to nomeFaixa(t.name, i + 1, true) }

    fun legendaAtual() = mp?.spuTrack ?: -1

    fun escolherLegenda(id: Int) {
        mp?.setSpuTrack(id)
        faixas++
    }

    fun carregarLegenda(f: File) {
        mp?.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(f), true)
        mostrarOsd("Legenda carregada")
        painel = null
        faixas++
    }

    fun definirVelocidade(r: Float) {
        mp?.setRate(r)
        velocidade = r
        mostrarOsd(if (r == 1f) "Velocidade normal" else "Velocidade ${fmtVelocidade(r)}")
    }

    fun definirAspecto(a: String) {
        aspecto = a
        aplicarAspecto()
    }

    private fun aplicarAspecto() {
        mp?.videoScale = when (aspecto) {
            "fill" -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            "16:9" -> MediaPlayer.ScaleType.SURFACE_16_9
            "4:3" -> MediaPlayer.ScaleType.SURFACE_4_3
            else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
        }
    }

    // ---------- Transmitir para a TV (Chromecast) ----------

    fun abrirTransmitir() {
        Transmissao.iniciar(app)   // a busca de aparelhos só começa aqui
        abrirPainel("transmitir")
    }

    /** Manda o vídeo para o Chromecast (alvo) ou de volta para este aparelho (null), continuando do mesmo ponto. */
    fun transmitir(alvo: RendererItem?) {
        val mp = mp ?: return
        if (fechado) return
        painel = null
        if (if (alvo == null) !transmitindo else transmitindo && Transmissao.ehOAtual(alvo)) return
        salvarProgresso()
        val t = if (req.isLive || mp.length <= 0) -1L else maxOf(0L, mp.time - 2000)
        val r = req
        ocupado = true
        // O VLC só liga o aparelho novo numa reprodução que começa depois da troca: para (fora da linha principal,
        // parar uma transmissão pode demorar), troca e reabre do mesmo ponto
        Thread {
            try { mp.stop() } catch (_: Exception) { }
            main.post {
                if (fechado || this.mp !== mp) return@post
                val ok = mp.setRenderer(alvo) == 0
                if (alvo != null && ok) ligarTransmissao(alvo) else { if (alvo != null) mp.setRenderer(null); desligarTransmissao() }
                Transmissao.definirAtual(if (ok) alvo else null)
                if (t >= 0) reabrirEm = t
                retries = 0
                start(r)
                mostrarOsd(if (cast != null) "Transmitindo para $transmitindoPara" else "Assistindo neste aparelho")
            }
        }.start()
    }

    private fun ligarTransmissao(a: RendererItem) {
        cast = a
        transmitindoPara = Transmissao.nome(a)
        Transmissao.avisarInicio(app, transmitindoPara!!)
    }

    private fun desligarTransmissao() {
        if (cast == null) return
        cast = null
        transmitindoPara = null
        Transmissao.avisarFim(app)
    }

    // ---------- Sair do app / voltar ----------

    fun aoParar() {
        val mp = mp ?: return
        if (fechado) return
        salvarProgresso()
        // transmitindo, a TV continua mostrando com o celular no bolso (o serviço mantém o app acordado)
        if (transmitindo) { if (anexado) { mp.detachViews(); anexado = false }; return }
        if (req.isLive) { recomecarAoVoltar = true; mp.stop() } else if (mp.isPlaying) mp.pause()
        if (anexado) { mp.detachViews(); anexado = false }
    }

    fun aoVoltar() {
        val mp = mp ?: return
        val l = layout ?: return
        if (fechado) return
        if (!anexado) { mp.attachViews(l, null, true, false); anexado = true }
        if (recomecarAoVoltar) { recomecarAoVoltar = false; start(req) } else mostrarControles()
    }

    // ---------- Encerrar ----------

    fun fechar() {
        if (fechado) return
        salvarProgresso()
        fechado = true
        Estado.fecharPlayer()
    }

    /** A tela do player saiu: para o VLC fora da linha principal (parar um stream pode demorar). */
    fun liberar() {
        if (!fechado) salvarProgresso()
        fechado = true
        Transmissao.parar()
        if (cast != null) { cast = null; Transmissao.avisarFim(app) }
        val mp = mp ?: return
        this.mp = null
        main.removeCallbacksAndMessages(null)
        try { mp.setEventListener(null) } catch (_: Exception) { }
        try { if (anexado) mp.detachViews() } catch (_: Exception) { }
        anexado = false
        Thread {
            try { mp.stop() } catch (_: Exception) { }
            try { mp.release() } catch (_: Exception) { }
        }.start()
    }
}

fun fmtVelocidade(r: Float): String = (if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()).replace('.', ',') + "x"

private val idiomas = mapOf(
    "english" to "Inglês", "eng" to "Inglês", "en" to "Inglês", "portuguese" to "Português", "por" to "Português", "pt" to "Português",
    "pt-br" to "Português (Brasil)", "brazilian portuguese" to "Português (Brasil)", "spanish" to "Espanhol", "spa" to "Espanhol", "es" to "Espanhol",
    "french" to "Francês", "fre" to "Francês", "fra" to "Francês", "german" to "Alemão", "ger" to "Alemão", "deu" to "Alemão",
    "italian" to "Italiano", "ita" to "Italiano", "japanese" to "Japonês", "jpn" to "Japonês", "korean" to "Coreano", "kor" to "Coreano",
    "chinese" to "Chinês", "chi" to "Chinês", "zho" to "Chinês", "russian" to "Russo", "rus" to "Russo", "undetermined" to "Indefinido", "und" to "Indefinido",
)

/** "Track 1 - [English]" -> "Inglês" */
fun nomeFaixa(raw0: String?, indice: Int, legenda: Boolean): String {
    val raw = raw0 ?: ""
    val m = Regex("\\[(.+?)]").find(raw)
    var lang = m?.groupValues?.get(1)?.trim()
    var rest = Regex("^\\s*(Track|Faixa|Pista)\\s*\\d+\\s*", RegexOption.IGNORE_CASE)
        .replace(if (m != null) raw.replace(m.value, "") else raw, "").trim(' ', '-', '–')
    if (lang != null) lang = idiomas[lang.lowercase(Locale.ROOT)] ?: lang.replaceFirstChar { it.uppercase() }
    idiomas[rest.lowercase(Locale.ROOT)]?.let { rest = it }
    if (lang != null) return if (rest.isNotEmpty()) "$lang ($rest)" else lang
    return rest.ifEmpty { if (legenda) "Legenda $indice" else "Faixa $indice" }
}
