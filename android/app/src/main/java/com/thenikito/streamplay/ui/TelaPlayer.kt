package com.thenikito.streamplay.ui

import android.content.ActivityNotFoundException
import android.content.pm.ActivityInfo
import android.net.Uri
import android.provider.OpenableColumns
import android.view.KeyEvent as KE
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.thenikito.streamplay.Estado
import com.thenikito.streamplay.Guia
import com.thenikito.streamplay.Item
import com.thenikito.streamplay.PlayRequest
import com.thenikito.streamplay.PlayerControle
import com.thenikito.streamplay.Store
import com.thenikito.streamplay.Transmissao
import com.thenikito.streamplay.fmtVelocidade
import com.thenikito.streamplay.tempo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

/** Player em tela cheia com controles no estilo da Netflix (toque e controle remoto). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TelaPlayer(req: PlayRequest) {
    val ctx = LocalContext.current
    val tv = LocalTv.current
    val m = medidas()
    val controle = usandoControle()
    val ctl = remember { PlayerControle(req, ctx.applicationContext) }
    val raiz = remember { FocusRequester() }
    val play = remember { FocusRequester() }
    val seek = remember { FocusRequester() }
    val tentar = remember { FocusRequester() }
    val view = LocalView.current
    val dono = LocalLifecycleOwner.current

    // tela cheia, deitada (no celular) e sem apagar enquanto toca
    DisposableEffect(Unit) {
        val act = ctx.activity()
        val antes = act?.requestedOrientation
        if (!tv) act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val ic = act?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        ic?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ic?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            ctl.liberar()
            view.keepScreenOn = false
            ic?.show(WindowInsetsCompat.Type.systemBars())
            if (act != null && antes != null) act.requestedOrientation = antes
        }
    }
    DisposableEffect(dono) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> ctl.aoParar()
                Lifecycle.Event.ON_START -> ctl.aoVoltar()
                else -> Unit
            }
        }
        dono.lifecycle.addObserver(obs)
        onDispose { dono.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) {
        try { ctl.preparar() } catch (e: Exception) { ctl.falhaMotor(e) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            ctl.tick()
        }
    }

    // foco do controle remoto: controles escondidos -> fundo (as setas viram atalhos); pedido -> botão certo
    LaunchedEffect(ctl.controles) {
        if (!ctl.controles) { delay(30); try { raiz.requestFocus() } catch (_: Exception) { } }
    }
    LaunchedEffect(ctl.pedidoFoco) {
        if (ctl.pedidoFoco == 0) { try { raiz.requestFocus() } catch (_: Exception) { }; return@LaunchedEffect }
        if (!controle && ctl.alvoFoco != "tentar") return@LaunchedEffect
        delay(40)
        val alvo = when (ctl.alvoFoco) { "seek" -> if (ctl.ehAoVivo) play else seek; "tentar" -> tentar; else -> play }
        try { alvo.requestFocus() } catch (_: Exception) { try { play.requestFocus() } catch (_: Exception) { } }
    }

    BackHandler {
        when {
            ctl.painel != null -> ctl.fecharPainel()
            tv && ctl.controles && ctl.tocando && ctl.erro == null && !ctl.raizFocada -> ctl.esconderControles()
            else -> ctl.fechar()
        }
    }

    val consumidas = remember { HashSet<Int>() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
            .onPreviewKeyEvent { ev ->
                val code = ev.key.nativeKeyCode
                when (ev.type) {
                    KeyEventType.KeyUp -> consumidas.remove(code)
                    KeyEventType.KeyDown -> teclas(ctl, code).also { if (it) consumidas.add(code) }
                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { c -> VLCVideoLayout(c).also { l -> l.post { ctl.anexar(l) } } },
            modifier = Modifier.fillMaxSize(),
        )
        // com erro, esconde a última imagem que ficou parada na tela (às vezes de outro canal)
        if (ctl.erro != null) Box(Modifier.fillMaxSize().background(Color.Black))
        ctl.transmitindoPara?.let { InfoTransmitindo(ctl, it) }

        // toque: mostra/esconde os controles; dois toques nas laterais voltam/avançam 10 s
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(raiz)
                .onFocusChanged { ctl.raizFocada = it.isFocused }
                .focusable()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { ctl.alternarControles() },
                        onDoubleTap = { o -> if (!ctl.ehAoVivo) ctl.skip(if (o.x < size.width / 2) -10_000 else 10_000) },
                    )
                },
        )

        if (ctl.ocupado && ctl.erro == null) CircularProgressIndicator(color = Cor.Vermelho, modifier = Modifier.align(Alignment.Center).size(56.dp))
        Lampejo(ctl, Modifier.align(Alignment.Center))

        AnimatedVisibility(ctl.pausadoInfo, enter = fadeIn(tween(700)), exit = fadeOut(tween(250))) { InfoPausado(ctl) }

        AnimatedVisibility(ctl.controles && ctl.erro == null, enter = fadeIn(tween(150)), exit = fadeOut(tween(400))) {
            Controles(ctl, play, seek)
        }

        ctl.retomar?.let { texto ->
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = m.margem, top = if (m.tv) 90.dp else 76.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Cor.Painel.copy(alpha = 0.95f))
                    .border(1.dp, Cor.Linha, RoundedCornerShape(10.dp))
                    .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(texto, color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.width(10.dp))
                Botao("Começar do início", { ctl.doInicio() }, tamanhoTexto = 13.sp)
            }
        }

        if (ctl.cartaoProximo && ctl.proximo != null && ctl.erro == null) CartaoProximo(ctl, Modifier.align(Alignment.BottomEnd))

        Osd(ctl, Modifier.align(Alignment.TopCenter))

        ctl.erro?.let { e ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 520.dp)
                    .padding(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Cor.Painel)
                    .border(1.dp, Cor.Linha, RoundedCornerShape(12.dp))
                    .padding(24.dp),
            ) {
                Text(e.titulo, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(e.texto, fontSize = 15.sp, color = Cor.Sub, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Botao("Tentar de novo", { ctl.tentarDeNovo() }, primario = true, focusRequester = tentar)
                    if (ctl.transmitindo) Botao("Assistir no celular", { ctl.transmitir(null) })
                    Botao("Voltar", { ctl.fechar() })
                }
            }
        }

        AnimatedVisibility(
            ctl.painel != null, modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it / 3 } + fadeIn(), exit = slideOutHorizontally { it / 3 } + fadeOut(),
        ) {
            Painel(ctl)
        }
    }
}

/** Atalhos do controle remoto. Devolve true quando a tecla foi usada. */
private fun teclas(ctl: PlayerControle, code: Int): Boolean {
    val live = ctl.ehAoVivo
    val atalho = ctl.raizFocada && ctl.painel == null && ctl.erro == null
    when (code) {
        KE.KEYCODE_MEDIA_PLAY_PAUSE, KE.KEYCODE_SPACE, KE.KEYCODE_HEADSETHOOK -> { ctl.togglePlay(); return true }
        KE.KEYCODE_MEDIA_PLAY -> { if (!ctl.tocando) ctl.togglePlay() else ctl.poke(); return true }
        KE.KEYCODE_MEDIA_PAUSE -> { if (ctl.tocando) ctl.togglePlay() else ctl.poke(); return true }
        KE.KEYCODE_MEDIA_FAST_FORWARD -> { ctl.skip(10_000); return true }
        KE.KEYCODE_MEDIA_REWIND -> { ctl.skip(-10_000); return true }
        KE.KEYCODE_MEDIA_NEXT -> { if (live) ctl.zap(1) else ctl.playNext(); return true }
        KE.KEYCODE_MEDIA_PREVIOUS -> { if (live) ctl.zap(-1); return true }
        KE.KEYCODE_CHANNEL_UP, KE.KEYCODE_PAGE_DOWN -> { ctl.zap(1); return true }
        KE.KEYCODE_CHANNEL_DOWN, KE.KEYCODE_PAGE_UP -> { ctl.zap(-1); return true }
        KE.KEYCODE_MEDIA_STOP -> { ctl.fechar(); return true }
    }
    if (!atalho) {
        ctl.poke()
        return false
    }
    when (code) {
        KE.KEYCODE_DPAD_CENTER, KE.KEYCODE_ENTER, KE.KEYCODE_NUMPAD_ENTER -> {
            if (ctl.cartaoProximo) ctl.playNext() else { ctl.poke(); ctl.pedirFoco("play") }
        }
        KE.KEYCODE_DPAD_LEFT -> { if (!live) ctl.skip(-10_000) else ctl.poke(); ctl.pedirFoco("seek") }
        KE.KEYCODE_DPAD_RIGHT -> { if (!live) ctl.skip(10_000) else ctl.poke(); ctl.pedirFoco("seek") }
        KE.KEYCODE_DPAD_UP -> if (live) ctl.zap(-1) else { ctl.poke(); ctl.pedirFoco("seek") }
        KE.KEYCODE_DPAD_DOWN -> if (live) ctl.zap(1) else { ctl.poke(); ctl.pedirFoco("play") }
        else -> { ctl.poke(); return false }
    }
    return true
}

@Composable
private fun Controles(ctl: PlayerControle, play: FocusRequester, seek: FocusRequester) {
    val m = medidas()
    val r = ctl.req
    Box(Modifier.fillMaxSize()) {
        // topo: "Voltar" e, embaixo, a capa com o nome e o episódio
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to Cor.Fundo.copy(alpha = 0.94f), 0.55f to Cor.Fundo.copy(alpha = 0.7f), 1f to Color.Transparent))
                .padding(horizontal = m.margem)
                .padding(top = if (m.tv) 24.dp else 12.dp, bottom = 28.dp),
        ) {
            Botao("Voltar", { ctl.fechar() }, icone = Icons.AutoMirrored.Filled.ArrowBack, tamanhoTexto = 15.sp)
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!r.item.logo.isNullOrBlank()) {
                    val larg = if (m.largo) 64.dp else 44.dp
                    Imagem(
                        r.item.logo, r.item.name,
                        Modifier.width(larg).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).border(1.dp, Cor.Linha, RoundedCornerShape(6.dp)),
                        tamanhoNome = 7.sp,
                    )
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(r.title, color = Color.White, fontSize = if (m.largo) 24.sp else 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val linha = r.episode?.let { "T${it.season} • E${it.number}   ${it.title}".trimEnd() } ?: if (r.isLive) r.item.category else r.item.meta
                    if (linha.isNotBlank()) Text(linha, color = Color(0xFFE4E4EA), fontSize = if (m.largo) 15.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp))
                }
            }
        }

        // rodapé: barra de tempo e botões
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Cor.Fundo.copy(alpha = 0.92f))))
                .padding(horizontal = m.margem)
                .padding(top = 40.dp, bottom = if (m.tv) 28.dp else 12.dp),
        ) {
            if (ctl.ehAoVivo) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    val pulso = rememberInfiniteTransition(label = "aovivo")
                    val a by pulso.animateFloat(1f, 0.25f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "ponto")
                    Box(Modifier.size(10.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(Cor.Vermelho))
                    Text("  AO VIVO", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    if (r.item.category.isNotEmpty()) Text("  •  ${r.item.category}", color = Cor.Sub, fontSize = 14.sp)
                }
                // guia de programação do canal
                if (Guia.versao.intValue >= 0 && Guia.temAlgum) {
                    val (a, s) = Guia.agoraESeguir(r.item, lembrarAgora(15_000))
                    if (a != null) Text(
                        "Agora: ${a.titulo}  ·  ${a.horario}", color = Color.White, fontSize = if (m.largo) 16.sp else 14.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (s != null) Text(
                        "A seguir: ${s.titulo}  ·  ${Guia.hhmm(s.inicio)}", color = Cor.Sub, fontSize = if (m.largo) 14.sp else 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                    )
                    if (a != null || s != null) Spacer(Modifier.height(8.dp))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BarraTempo(ctl, seek, Modifier.weight(1f))
                    Spacer(Modifier.width(14.dp))
                    val resta = if (ctl.arrastando) ((1 - ctl.posArrasto) * ctl.duracao).toLong() else ctl.duracao - ctl.tempo
                    Text(if (ctl.duracao > 0) tempo(resta) else "", color = Color.White, fontSize = 14.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                BotaoPlayer(if (ctl.tocando) Icones.Pausa else Icons.Filled.PlayArrow, if (ctl.tocando) "Pausar" else "Reproduzir", false, play) { ctl.togglePlay() }
                if (!ctl.ehAoVivo) {
                    BotaoPlayer(Icones.Voltar10, "Voltar 10 segundos", false) { ctl.skip(-10_000) }
                    BotaoPlayer(Icones.Avancar10, "Avançar 10 segundos", false) { ctl.skip(10_000) }
                }
                if (ctl.variosCanais) {
                    BotaoPlayer(Icones.Acima, "Canal anterior", false) { ctl.zap(-1) }
                    BotaoPlayer(Icones.Abaixo, "Próximo canal", false) { ctl.zap(1) }
                }
                Spacer(Modifier.weight(1f))
                val textos = m.largo
                if (ctl.proximo != null) BotaoPlayer(Icones.Proximo, "Próximo episódio", textos) { ctl.playNext() }
                if (ctl.temEpisodios) BotaoPlayer(Icones.Episodios, "Episódios", textos) { ctl.abrirPainel("episodios") }
                if (ctl.variosCanais) BotaoPlayer(Icones.Tv, "Canais", textos) { ctl.abrirPainel("canais") }
                // Chromecast só no celular/tablet: na TV o vídeo já está na TV. Só o ícone (como no YouTube): com
                // texto a fileira não cabe na tela deitada do celular
                if (!m.tv) BotaoPlayer(Icones.Transmitir, if (ctl.transmitindo) "Transmitindo" else "Transmitir para a TV", false, ativo = ctl.transmitindo) { ctl.abrirTransmitir() }
                BotaoPlayer(Icones.Legendas, "Áudio e legendas", textos) { ctl.abrirPainel("legendas") }
                if (!ctl.ehAoVivo && !ctl.transmitindo) BotaoPlayer(Icones.Velocidade, "Velocidade", textos) { ctl.abrirPainel("velocidade") }
            }
        }
    }
}

@Composable
private fun BotaoPlayer(icone: ImageVector, texto: String, comTexto: Boolean, fr: FocusRequester? = null, ativo: Boolean = false, onClick: () -> Unit) {
    var focado by remember { mutableStateOf(false) }
    Row(
        Modifier
            .padding(horizontal = 2.dp)
            .foco(RoundedCornerShape(50), escala = 1.08f, borda = false, focusRequester = fr, aoFocar = { focado = it }, onClick = onClick)
            .background(if (focado) Color.White else Color.Transparent)
            .padding(horizontal = if (comTexto) 12.dp else 9.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icone, texto, tint = if (focado) Color.Black else if (ativo) Cor.Vermelho else Color.White, modifier = Modifier.size(28.dp))
        if (comTexto) Text(texto, color = if (focado) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun BarraTempo(ctl: PlayerControle, fr: FocusRequester, modifier: Modifier) {
    var focado by remember { mutableStateOf(false) }
    val frac = if (ctl.arrastando) ctl.posArrasto else if (ctl.duracao > 0) (ctl.tempo.toFloat() / ctl.duracao).coerceIn(0f, 1f) else 0f
    BoxWithConstraints(
        modifier
            .height(36.dp)
            .focusRequester(fr)
            .onFocusChanged { focado = it.isFocused }
            .onKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (it.key.nativeKeyCode) {
                    KE.KEYCODE_DPAD_LEFT -> { ctl.skip(-10_000); true }
                    KE.KEYCODE_DPAD_RIGHT -> { ctl.skip(10_000); true }
                    KE.KEYCODE_DPAD_CENTER, KE.KEYCODE_ENTER -> { ctl.togglePlay(); true }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(Unit) { detectTapGestures { o -> ctl.irPara(o.x / size.width) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> ctl.arrastar(o.x / size.width) },
                    onDragEnd = { ctl.soltar() },
                    onDragCancel = { ctl.soltar() },
                ) { change, _ -> ctl.arrastar(change.position.x / size.width) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val w = maxWidth
        val alt = if (focado || ctl.arrastando) 6.dp else 4.dp
        Box(Modifier.fillMaxWidth().height(alt).clip(RoundedCornerShape(50)).background(Color(0x4DFFFFFF)))
        Box(Modifier.width(w * frac).height(alt).clip(RoundedCornerShape(50)).background(Cor.Vermelho))
        val bola = if (focado || ctl.arrastando) 18.dp else 13.dp
        Box(
            Modifier
                .offset(x = w * frac - bola / 2)
                .size(bola)
                .clip(CircleShape)
                .background(Cor.Vermelho)
                .border(2.dp, Color.White, CircleShape),
        )
        if (ctl.arrastando && ctl.duracao > 0) Text(
            tempo((ctl.posArrasto * ctl.duracao).toLong()), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .offset(x = (w * frac - 28.dp).coerceIn(0.dp, w - 56.dp), y = (-26).dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Cor.Painel)
                .padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun Lampejo(ctl: PlayerControle, modifier: Modifier) {
    val f = ctl.flash ?: return
    val a = remember(f) { Animatable(1f) }
    LaunchedEffect(f) { a.animateTo(0f, tween(650)) }
    val m = medidas()
    when (f.tipo) {
        "rew", "fwd" -> Box(Modifier.fillMaxSize(), contentAlignment = if (f.tipo == "rew") Alignment.CenterStart else Alignment.CenterEnd) {
            Column(
                Modifier.padding(horizontal = m.larguraTela / 8).graphicsLayer { alpha = a.value },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(if (f.tipo == "rew") Icones.Voltar10 else Icones.Avancar10, null, tint = Color.White, modifier = Modifier.size(56.dp))
                Text("10 s", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
        else -> Box(
            modifier
                .size(84.dp)
                .graphicsLayer { alpha = a.value; val s = 0.85f + 0.4f * (1 - a.value); scaleX = s; scaleY = s }
                .clip(CircleShape)
                .background(Color(0x99000000)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (f.tipo == "pause") Icones.Pausa else Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(46.dp))
        }
    }
}

@Composable
private fun Osd(ctl: PlayerControle, modifier: Modifier) {
    val o = ctl.osd
    var visivel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(o) {
        if (o == null) return@LaunchedEffect
        visivel = o.texto
        delay(1700)
        visivel = null
    }
    AnimatedVisibility(visivel != null, modifier = modifier.padding(top = 150.dp), enter = fadeIn(tween(80)), exit = fadeOut(tween(400))) {
        var ultimo by remember { mutableStateOf("") }
        visivel?.let { ultimo = it }
        Text(
            ultimo, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Cor.Painel.copy(alpha = 0.9f)).border(1.dp, Cor.Linha, RoundedCornerShape(10.dp)).padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun InfoPausado(ctl: PlayerControle) {
    val r = ctl.req
    val m = medidas()
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Cor.Fundo.copy(alpha = 0.92f), Color.Transparent)))) {
        Column(Modifier.align(Alignment.CenterStart).padding(start = m.margem * 2).widthIn(max = 560.dp)) {
            Text("Você está assistindo", color = Cor.Sub, fontSize = 15.sp)
            Text(r.title, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black, lineHeight = 40.sp, modifier = Modifier.padding(vertical = 6.dp))
            r.episode?.let { Text("${it.label}  \"${it.title}\"", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
            val programa = if (r.isLive) Guia.agoraESeguir(r.item, System.currentTimeMillis()).first else null
            programa?.let { Text("Agora: ${it.titulo}  ·  ${it.horario}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
            val plot = r.episode?.plot ?: r.item.plot ?: programa?.descricao
            if (!plot.isNullOrBlank()) Text(plot, color = Cor.Sub, fontSize = 15.sp, lineHeight = 22.sp, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
        }
        Text("Pausado", color = Cor.Sub, fontSize = 15.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(m.margem))
    }
}

@Composable
private fun CartaoProximo(ctl: PlayerControle, modifier: Modifier) {
    val m = medidas()
    val p = ctl.proximo ?: return
    Column(
        modifier
            .padding(end = m.margem, bottom = if (ctl.controles) 130.dp else 40.dp)
            .widthIn(max = 380.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Cor.Painel)
            .border(1.dp, Cor.Linha, RoundedCornerShape(12.dp))
            .padding(18.dp),
    ) {
        Text("Próximo episódio", color = Cor.Sub, fontSize = 13.sp)
        Text("${p.label}  ${p.title}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            var f by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .foco(RoundedCornerShape(6.dp), escala = 1.06f, borda = false, aoFocar = { f = it }) { ctl.playNext() }
                    .background(if (f) Color.White else Cor.Vermelho),
            ) {
                if (Store.autoNext) Box(Modifier.matchParentSize().fillMaxWidth(ctl.fracProximo).background(Color(0x4D000000)))
                Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PlayArrow, null, tint = if (f) Color.Black else Color.White, modifier = Modifier.size(20.dp))
                    Text("Assistir", color = if (f) Color.Black else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
                }
            }
            Botao("Ver créditos", { ctl.dispensarCreditos() }, tamanhoTexto = 15.sp)
        }
    }
}

// ---------- Painéis (áudio e legendas, velocidade, episódios, canais) ----------

@Composable
private fun Painel(ctl: PlayerControle) {
    val m = medidas()
    Box(
        Modifier
            .fillMaxHeight()
            .width(if (m.largo) 400.dp else (m.larguraTela * 0.88f))
            .background(Cor.Painel.copy(alpha = 0.97f))
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        when (ctl.painel) {
            "legendas" -> PainelLegendas(ctl)
            "velocidade" -> PainelVelocidade(ctl)
            "episodios" -> PainelEpisodios(ctl)
            "canais" -> PainelCanais(ctl)
            "transmitir" -> PainelTransmitir(ctl)
        }
    }
}

@Composable
private fun PainelTransmitir(ctl: PlayerControle) {
    val primeiro = focoInicial()
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Titulo("Transmitir para a TV") }
        item { Opcao("Este celular", !ctl.transmitindo, primeiro) { ctl.transmitir(null) } }
        itemsIndexed(Transmissao.aparelhos.toList()) { _, a ->
            Opcao(Transmissao.nome(a), ctl.transmitindo && Transmissao.ehOAtual(a)) { ctl.transmitir(a) }
        }
        if (Transmissao.aparelhos.isEmpty()) item {
            Row(Modifier.padding(start = 46.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Cor.Sub, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Text("Procurando aparelhos na rede...", color = Cor.Dim, fontSize = 15.sp, modifier = Modifier.padding(start = 10.dp))
            }
        }
        item {
            Text(
                "Funciona com Chromecast e TVs com Chromecast embutido. A TV e este celular precisam estar no mesmo Wi-Fi, " +
                    "e o StreamPLAY fica aberto (pode apagar a tela) enquanto a TV mostra o vídeo.",
                color = Cor.Dim, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
            )
        }
    }
}

/** Transmitindo: o vídeo está na TV; aqui fica o aviso (e os controles por cima). */
@Composable
private fun InfoTransmitindo(ctl: PlayerControle, aparelho: String) {
    val r = ctl.req
    Box(Modifier.fillMaxSize().background(Cor.Fundo), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 32.dp).widthIn(max = 640.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icones.Transmitir, null, tint = Cor.Vermelho, modifier = Modifier.size(64.dp))
            Text("Transmitindo para $aparelho", color = Cor.Sub, fontSize = 17.sp, modifier = Modifier.padding(top = 16.dp))
            Text(r.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp, modifier = Modifier.padding(top = 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            r.episode?.let { Text("T${it.season} • E${it.number}   ${it.title}".trimEnd(), color = Color(0xFFD0D0D0), fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

@Composable
private fun Titulo(texto: String) {
    Text(texto, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(start = 20.dp, top = 22.dp, bottom = 10.dp))
}

@Composable
private fun Opcao(texto: String, selecionado: Boolean, fr: FocusRequester? = null, inicio: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .foco(RoundedCornerShape(0.dp), escala = 1f, borda = false, focusRequester = fr, aoFocar = { f = it }, onClick = onClick)
            .background(if (f) Color.White else if (selecionado) Color(0x33FFFFFF) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(30.dp)) {
            if (selecionado) Icon(Icons.Filled.Check, null, tint = if (f) Color.Black else Color.White, modifier = Modifier.size(20.dp))
        }
        if (inicio != null) { inicio(); Spacer(Modifier.width(12.dp)) }
        Text(
            texto, color = if (f) Color.Black else if (selecionado) Color.White else Cor.Sub, fontSize = 16.sp,
            fontWeight = if (selecionado) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun focoInicial(): FocusRequester {
    val fr = remember { FocusRequester() }
    val controle = usandoControle()
    LaunchedEffect(Unit) { if (controle) { delay(80); try { fr.requestFocus() } catch (_: Exception) { } } }
    return fr
}

@Composable
private fun PainelLegendas(ctl: PlayerControle) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    @Suppress("UNUSED_VARIABLE") val v = ctl.faixas
    val audios = ctl.faixasAudio()
    val legendas = ctl.faixasLegenda()
    val aAtual = ctl.audioAtual()
    val lAtual = ctl.legendaAtual()
    val primeiro = focoInicial()
    val escolher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val f = withContext(Dispatchers.IO) {
                try {
                    var nome = "legenda.srt"
                    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0)?.let { n -> if (n.contains('.')) nome = "legenda." + n.substringAfterLast('.') }
                    }
                    val dir = File(ctx.cacheDir, "legendas").apply { mkdirs() }
                    File(dir, nome).also { out -> ctx.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } } }
                } catch (_: Exception) {
                    null
                }
            }
            if (f != null && f.length() > 0) ctl.carregarLegenda(f) else ctl.mostrarOsd("Não consegui abrir a legenda")
        }
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Titulo("Áudio") }
        if (audios.isEmpty()) item { Text("Padrão", color = Cor.Dim, fontSize = 16.sp, modifier = Modifier.padding(start = 46.dp, bottom = 6.dp)) }
        itemsIndexed(audios) { i, (id, nome) -> Opcao(nome, id == aAtual, if (i == 0) primeiro else null) { ctl.escolherAudio(id) } }
        item { Titulo("Legendas") }
        item { Opcao("Desativadas", lAtual == -1, if (audios.isEmpty()) primeiro else null) { ctl.escolherLegenda(-1) } }
        itemsIndexed(legendas) { _, (id, nome) -> Opcao(nome, id == lAtual) { ctl.escolherLegenda(id) } }
        item {
            Opcao("+ Carregar arquivo de legenda...", false) {
                try { escolher.launch(arrayOf("*/*")) } catch (_: ActivityNotFoundException) { ctl.mostrarOsd("Este aparelho não tem seletor de arquivos") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PainelVelocidade(ctl: PlayerControle) {
    val primeiro = focoInicial()
    Column(Modifier.padding(bottom = 24.dp)) {
        Titulo("Velocidade")
        FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (r in listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f)) {
                Chip(if (r == 1f) "1x (Normal)" else fmtVelocidade(r), ctl.velocidade == r, { ctl.definirVelocidade(r) }, focusRequester = if (r == 1f) primeiro else null)
            }
        }
        Titulo("Imagem")
        FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((k, nome) in listOf("original" to "Original", "fill" to "Preencher a tela", "16:9" to "16:9", "4:3" to "4:3")) {
                Chip(nome, ctl.aspecto == k, { ctl.definirAspecto(k) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PainelEpisodios(ctl: PlayerControle) {
    val r = ctl.req
    val show = r.item
    val atual = r.episode ?: return
    var temporada by remember { mutableIntStateOf(atual.season) }
    val eps = remember(temporada) { show.episodes.filter { it.season == temporada } }
    val idx = eps.indexOf(atual).coerceAtLeast(0)
    val estado = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, idx - 2))
    val primeiro = focoInicial()
    val seasons = show.seasons
    LazyColumn(state = estado, contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Titulo(show.name) }
        if (seasons.size > 1) item {
            FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in seasons) Chip("Temporada $s", s == temporada, { temporada = s })
            }
        }
        listaEpisodios(eps, atual, idx, primeiro, ctl)
    }
}

private fun LazyListScope.listaEpisodios(
    eps: List<com.thenikito.streamplay.Episode>,
    atual: com.thenikito.streamplay.Episode,
    idx: Int,
    primeiro: FocusRequester,
    ctl: PlayerControle,
) {
    itemsIndexed(eps) { i, ep ->
        val p = Store.getProgress(ep.key)
        Opcao(
            "${ep.number}. ${ep.title}", ep == atual, if (i == idx) primeiro else null,
            inicio = if (p != null) ({ BarraProgresso(if (p.finished) 1f else p.fraction.toFloat(), Modifier.width(36.dp)) }) else null,
        ) {
            if (ep != atual) ctl.trocar(PlayRequest.forEpisode(ep)!!) else ctl.fecharPainel()
        }
    }
}

@Composable
private fun PainelCanais(ctl: PlayerControle) {
    val r = ctl.req
    val lista: List<Item> = r.channels ?: return
    val idx = lista.indexOf(r.item).coerceAtLeast(0)
    val estado = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, idx - 3))
    val primeiro = focoInicial()
    LazyColumn(state = estado, contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Titulo("Canais") }
        itemsIndexed(lista) { i, ch ->
            Opcao(ch.name, ch == r.item, if (i == idx) primeiro else null, inicio = {
                Imagem(ch.logo, ch.name, Modifier.size(52.dp, 30.dp).clip(RoundedCornerShape(3.dp)), crop = false, tamanhoNome = 7.sp)
            }) {
                if (ch != r.item) ctl.trocar(PlayRequest.forChannel(ch, lista)) else ctl.fecharPainel()
            }
        }
    }
}
