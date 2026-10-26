package com.thenikito.streamplay.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thenikito.streamplay.Estado
import com.thenikito.streamplay.Kind
import com.thenikito.streamplay.Tela
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun Raiz() {
    Box(Modifier.fillMaxSize().background(Cor.Fundo)) {
        when (val t = Estado.tela) {
            is Tela.Login -> TelaLogin(t.aviso)
            is Tela.Bloqueio -> TelaBloqueio(t.r)
            is Tela.Fonte -> TelaFonte(t)
            Tela.Principal -> TelaPrincipal()
            Tela.Abertura -> Unit
        }

        val player = Estado.player
        if (player == null) {
            Estado.detalhes?.let { key(it) { TelaDetalhes(it) } }
            if (Estado.ajustes) TelaAjustes()
        } else {
            key(player) { TelaPlayer(player) }
        }

        BannerAtualizacao()
        Abertura()
        Avisos()
    }

    // a janela de cima fechou: o foco do controle volta para o cartão de onde ela foi aberta
    val livre = Estado.detalhes == null && !Estado.ajustes && Estado.player == null
    val controle = usandoControle()
    LaunchedEffect(livre) {
        if (livre && controle) {
            delay(60)
            Estado.voltarFoco()
        }
    }
}

@Composable
fun TelaPrincipal() {
    val lib = Estado.lib ?: return
    Box(Modifier.fillMaxSize()) {
        when (Estado.tab) {
            "channels" -> key(lib) { TelaCanais(lib) }
            "series" -> key(lib, "series") { TelaNavegar(lib, Kind.Series) }
            "mylist" -> key(lib, "mylist") { TelaNavegar(lib, null) }
            else -> key(lib, "movies") { TelaNavegar(lib, Kind.Movie) }
        }
        BarraTopo()
    }
}

/** Altura da barra de cima (sem a barra de status), para o conteúdo começar logo abaixo dela. */
@Composable
fun alturaTopo(): androidx.compose.ui.unit.Dp {
    val m = medidas()
    return if (m.tv) 80.dp else if (m.largo) 68.dp else 100.dp
}

@Composable
fun BarraTopo() {
    val m = medidas()
    val solido = if (Estado.tab == "channels") 1f else Estado.topoSolido
    var buscando by remember { mutableStateOf(Estado.busca.isNotEmpty()) }
    val campo = remember { FocusRequester() }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xD9000000), Color.Transparent)))
            .background(Cor.Fundo.copy(alpha = solido))
            .then(if (m.largo) Modifier.descerParaConteudo() else Modifier)
            .statusBarsPadding()
            .padding(horizontal = m.margem)
            .padding(top = if (m.tv) 18.dp else 8.dp, bottom = 6.dp),
    ) {
        Row(Modifier.height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!(buscando && !m.largo)) Logo(Modifier.height(if (m.largo) 30.dp else 24.dp))
            if (m.largo) {
                Spacer(Modifier.width(28.dp))
                Abas()
            }
            Spacer(Modifier.weight(1f))
            if (buscando) {
                CampoBusca(
                    campo,
                    Modifier.then(if (m.largo) Modifier.width(260.dp) else Modifier.weight(100f)),
                    fechar = { buscando = false; Estado.limparBusca() },
                )
                LaunchedEffect(Unit) { try { campo.requestFocus() } catch (_: Exception) { } }
            } else {
                IconeBotao(Icons.Filled.Search, "Buscar", { buscando = true })
            }
            Spacer(Modifier.width(6.dp))
            IconeBotao(Icons.Filled.Settings, "Configurações", { Estado.abrirAjustes() })
        }
        if (!m.largo) Abas(Modifier.padding(top = 2.dp).descerParaConteudo())
    }
}

@Composable
private fun Abas(modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((t, nome) in listOf("channels" to "Canais", "movies" to "Filmes", "series" to "Séries", "mylist" to "Minha lista")) {
            var focado by remember { mutableStateOf(false) }
            val sel = Estado.tab == t
            Text(
                nome,
                color = if (focado) Color.Black else if (sel) Color.White else Cor.Texto,
                fontSize = 15.sp,
                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .foco(RoundedCornerShape(50), escala = 1.06f, borda = false, aoFocar = { focado = it }) { Estado.navegar(t) }
                    .background(if (focado) Color.White else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun CampoBusca(fr: FocusRequester, modifier: Modifier, fechar: () -> Unit) {
    var focado by remember { mutableStateOf(false) }
    val teclado = LocalSoftwareKeyboardController.current
    val foco = LocalFocusManager.current
    val controle = usandoControle()
    val escopo = rememberCoroutineScope()
    // pesquisar (ou Enter): fecha o teclado e leva o foco para o primeiro resultado (na TV o teclado ocupa a tela)
    fun irParaResultados() {
        teclado?.hide()
        // no celular (toque): só tira o foco da caixa, para o teclado não voltar; na TV o foco vai para os resultados
        if (!controle) { foco.clearFocus(); return }
        escopo.launch {
            delay(400)
            try { Estado.alvoConteudo?.requestFocus() } catch (_: Exception) { }
        }
    }
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xCC000000))
            .border(1.dp, if (focado) Color.White else Color(0xFF808080), RoundedCornerShape(4.dp))
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = Color.White, modifier = Modifier.padding(end = 8.dp))
        Box(Modifier.weight(1f)) {
            if (Estado.busca.isEmpty()) Text("Títulos, canais...", color = Cor.Dim, fontSize = 15.sp)
            BasicTextField(
                value = Estado.busca,
                onValueChange = { Estado.busca = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { irParaResultados() }, onDone = { irParaResultados() }),
                modifier = Modifier.fillMaxWidth().focusRequester(fr).onFocusChanged { focado = it.isFocused },
            )
        }
        IconeBotao(Icons.Filled.Close, "Fechar busca", fechar, tamanho = 36.dp)
    }
}

/** Tela de abertura com o logotipo (enquanto confere a conta e carrega a lista). */
@Composable
fun Abertura() {
    AnimatedVisibility(Estado.abertura, enter = fadeIn(tween(0)), exit = fadeOut(tween(380))) {
        val anim = remember { Animatable(0f) }
        val texto = remember { Animatable(0f) }
        LaunchedEffect(Unit) { anim.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
        LaunchedEffect(Unit) { delay(900); texto.animateTo(1f, tween(400)) }
        Box(
            Modifier
                .fillMaxSize()
                .background(Cor.Fundo)
                .pointerInput(Unit) { },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Logo(
                    Modifier
                        .fillMaxWidth(0.62f)
                        .widthIn(max = 560.dp)
                        .graphicsLayer {
                            alpha = (anim.value * 2.2f).coerceAtMost(1f)
                            val s = 0.86f + 0.14f * anim.value
                            scaleX = s
                            scaleY = s
                        },
                )
                Spacer(Modifier.height(28.dp))
                Text(
                    Estado.textoAbertura, color = Cor.Sub, fontSize = 15.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer { alpha = texto.value },
                )
            }
        }
    }
}

/** Aviso rápido no rodapé (some sozinho depois de 3,5 s). */
@Composable
fun Avisos() {
    val a = Estado.aviso
    LaunchedEffect(a) {
        if (a != null && !a.fixo) {
            delay(3500)
            if (Estado.aviso === a) Estado.aviso = null
        }
    }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 40.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(a != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(300))) {
            var ultimo by remember { mutableStateOf("") }
            if (a != null) ultimo = a.texto
            Text(
                ultimo, color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xF0262626))
                    .border(1.dp, Color(0xFF3A3A3A), RoundedCornerShape(8.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
fun BannerAtualizacao() {
    val v = Estado.novaVersao
    if (v == null) return
    val mostrar = Estado.bannerAtualizacao && Estado.tela == Tela.Principal && Estado.player == null &&
        Estado.detalhes == null && !Estado.ajustes
    if (!mostrar) return
    val ctx = LocalContext.current
    val primeiro = remember { FocusRequester() }
    val controle = usandoControle()
    LaunchedEffect(Unit) { if (controle) { delay(300); try { primeiro.requestFocus() } catch (_: Exception) { } } }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(24.dp), contentAlignment = Alignment.BottomEnd) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xF2202020))
                .border(1.dp, Cor.Linha, RoundedCornerShape(8.dp))
                .pointerInput(Unit) { }
                .padding(20.dp),
        ) {
            Text("StreamPLAY ${v.versao} disponível", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                "Tem uma versão nova do app. Atualize para ter as últimas correções.",
                fontSize = 14.sp, color = Cor.Sub, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Botao("Atualizar agora", { ctx.activity()?.let { Estado.instalarAtualizacao(it) } }, primario = true, focusRequester = primeiro)
                Botao("Depois", { Estado.bannerAtualizacao = false })
            }
        }
    }
}

/** Fundo escuro que bloqueia toques na tela de baixo. */
fun Modifier.bloqueiaToques(aoTocar: (() -> Unit)? = null) = this.pointerInput(aoTocar) {
    detectTapGestures { aoTocar?.invoke() }
}

/** O conteúdo fica por baixo da barra de cima, então a busca de foco para baixo não acha nada: leva direto ao alvo. */
private fun Modifier.descerParaConteudo() = this.onPreviewKeyEvent {
    if (it.type != KeyEventType.KeyDown || it.key.nativeKeyCode != android.view.KeyEvent.KEYCODE_DPAD_DOWN) return@onPreviewKeyEvent false
    val alvo = Estado.alvoConteudo ?: return@onPreviewKeyEvent false
    try { alvo.requestFocus(); true } catch (_: Exception) { false }
}
