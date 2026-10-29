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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.window.Dialog
import com.thenikito.streamplay.Atualizador
import com.thenikito.streamplay.Conta
import com.thenikito.streamplay.Dia
import com.thenikito.streamplay.Store
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
import kotlin.math.roundToInt

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
            "movies" -> key(lib, "movies") { TelaNavegar(lib, Kind.Movie) }
            else -> key(lib, "home") { TelaNavegar(lib, null, inicio = true) }
        }
        BarraTopo()
    }
}

/** Altura da barra de cima (sem a barra de status), para o conteúdo começar logo abaixo dela. */
@Composable
fun alturaTopo(): androidx.compose.ui.unit.Dp {
    val m = medidas()
    return if (!m.largo) 108.dp else if (m.tv) 80.dp else 68.dp   // tela estreita: logo + abas embaixo
}

@Composable
fun BarraTopo() {
    val m = medidas()
    var buscando by remember { mutableStateOf(Estado.busca.isNotEmpty()) }
    var janela by remember { mutableStateOf<String?>(null) }   // "avisos" ou "conta"
    val campo = remember { FocusRequester() }

    // sempre sólida (nada de degradê por cima do banner), com a linha vermelha embaixo que some nas pontas
    Column(
        Modifier
            .fillMaxWidth()
            .background(Cor.Fundo)
            .then(if (m.largo) Modifier.descerParaConteudo() else Modifier)
            .statusBarsPadding(),
    ) {
        Column(Modifier.padding(horizontal = m.margem).padding(top = if (m.tv) 18.dp else 8.dp, bottom = 5.dp)) {
            Row(Modifier.height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!(buscando && !m.largo)) Logo(altura = if (m.largo) 26.dp else 21.dp)
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
                Spacer(Modifier.width(4.dp))
                Box {
                    IconeBotao(Icones.Sino, "Avisos", { janela = "avisos"; Estado.avisosNovos = false })
                    if (Estado.avisosNovos) Box(
                        Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 9.dp).size(9.dp)
                            .clip(CircleShape).background(Cor.Vermelho).border(1.5.dp, Cor.Fundo, CircleShape),
                    )
                }
                Spacer(Modifier.width(4.dp))
                IconeBotao(Icones.Usuario, "Minha conta", { janela = "conta" })
            }
            if (!m.largo) Abas(Modifier.descerParaConteudo())
        }
        Box(
            Modifier.fillMaxWidth().height(1.dp).background(
                Brush.horizontalGradient(0f to Color.Transparent, 0.25f to Color(0xA6E50914), 0.75f to Color(0xA6E50914), 1f to Color.Transparent),
            ),
        )
    }
    when (janela) {
        "avisos" -> DialogoAvisos { janela = null }
        "conta" -> DialogoConta { janela = null }
    }
}

@Composable
private fun Abas(modifier: Modifier = Modifier) {
    val abas = listOf(
        Triple("home", "Início", Icones.Inicio), Triple("channels", "Canais", Icones.AoVivo), Triple("movies", "Filmes", Icones.Filme),
        Triple("series", "Séries", Icones.Serie), Triple("mylist", "Minha lista", Icones.Marcador),
    )
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((t, nome, icone) in abas) {
            var focado by remember { mutableStateOf(false) }
            val sel = Estado.tab == t
            val cor = if (focado) Color.Black else if (sel) Cor.VermelhoClaro else Cor.Texto
            Row(
                Modifier
                    .foco(RoundedCornerShape(8.dp), escala = 1.06f, borda = false, aoFocar = { focado = it }) { Estado.navegar(t) }
                    .background(if (focado) Color.White else Color.Transparent)
                    // a aba aberta ganha o traço vermelho embaixo
                    .drawBehind {
                        if (sel && !focado) drawRect(Cor.Vermelho, topLeft = Offset(0f, size.height - 3.dp.toPx()), size = Size(size.width, 3.dp.toPx()))
                    }
                    .padding(horizontal = 11.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icone, null, tint = cor, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(7.dp))
                Text(nome, color = cor, fontSize = 15.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

/** Janela do sino: avisos guardados (vencimento, atualização, lista atualizada). */
@Composable
private fun DialogoAvisos(fechar: () -> Unit) {
    val fr = remember { FocusRequester() }
    val controle = usandoControle()
    Dialog(onDismissRequest = fechar) {
        Column(Modifier.widthIn(max = 420.dp).clip(RoundedCornerShape(12.dp)).background(Cor.Painel).border(1.dp, Cor.Linha, RoundedCornerShape(12.dp)).padding(20.dp)) {
            Text("Avisos", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White)
            if (Estado.avisos.isEmpty()) Text("Nenhum aviso por enquanto.", fontSize = 15.sp, color = Cor.Sub, modifier = Modifier.padding(top = 10.dp))
            for (a in Estado.avisos) Column(Modifier.padding(top = 12.dp)) {
                Text(a.first, fontSize = 15.sp, color = Color(0xFFEDEDF2))
                Text(a.second, fontSize = 12.sp, color = Cor.Dim, modifier = Modifier.padding(top = 2.dp))
            }
            Botao("Fechar", fechar, Modifier.padding(top = 18.dp), focusRequester = fr)
        }
    }
    LaunchedEffect(Unit) { if (controle) { delay(150); try { fr.requestFocus() } catch (_: Exception) { } } }
}

/** Janela do perfil: nome, login, vencimento, Configurações e Sair da conta. */
@Composable
private fun DialogoConta(fechar: () -> Unit) {
    val fr = remember { FocusRequester() }
    val controle = usandoControle()
    var sair by remember { mutableStateOf(false) }
    val a = Estado.contaAtual?.acesso
    Dialog(onDismissRequest = fechar) {
        Column(Modifier.widthIn(max = 420.dp).clip(RoundedCornerShape(12.dp)).background(Cor.Painel).border(1.dp, Cor.Linha, RoundedCornerShape(12.dp)).padding(20.dp)) {
            Text(a?.nome?.takeIf { it.isNotBlank() } ?: "Minha conta", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Store.login?.takeIf { it.isNotBlank() }?.let { Text("Login: $it", fontSize = 14.sp, color = Cor.Sub, modifier = Modifier.padding(top = 3.dp)) }
            val venc = when {
                a?.faturaVenc != null -> "Próximo vencimento: ${Dia.fmt(a.faturaVenc)}" + (a.faturaValor?.let { "  •  ${Conta.dinheiro(it)}" } ?: "")
                a?.liberadoAte != null -> "Acesso liberado até ${Dia.fmt(a.liberadoAte)}"
                else -> "Acesso liberado"
            }
            Text(venc, fontSize = 15.sp, color = Color(0xFFD8D8E0), modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Botao("Configurações", { fechar(); Estado.abrirAjustes() }, focusRequester = fr)
                Botao("Sair da conta", { sair = true })
            }
        }
    }
    if (sair) Confirmar(
        "Sair da sua conta? Para usar o StreamPLAY de novo, será preciso digitar o login e a senha.", "Sair",
        aoSim = { sair = false; fechar(); Estado.sairDaConta(null) }, aoNao = { sair = false },
    )
    LaunchedEffect(Unit) { if (controle) { delay(150); try { fr.requestFocus() } catch (_: Exception) { } } }
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
                        .graphicsLayer {
                            alpha = (anim.value * 2.2f).coerceAtMost(1f)
                            val s = 0.86f + 0.14f * anim.value
                            scaleX = s
                            scaleY = s
                        },
                    altura = if (medidas().largo) 60.dp else 36.dp,
                )
                Spacer(Modifier.height(28.dp))
                // carregando a lista: só a barra com a %, sem texto
                val p = Estado.andamento
                if (p >= 0f && Estado.textoAbertura.isEmpty()) Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.graphicsLayer { alpha = texto.value },
                ) {
                    Text("${(p * 100).roundToInt()}%", color = Cor.Sub, fontSize = 15.sp)
                    BarraAndamento(p, Modifier.padding(top = 12.dp).width(if (medidas().largo) 320.dp else 220.dp))
                } else Text(
                    Estado.textoAbertura, color = Cor.Sub, fontSize = 15.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer { alpha = texto.value },
                )
            }
        }
    }
}

/** Barra fina de andamento (0 a 1), vermelha sobre um trilho claro. */
@Composable
fun BarraAndamento(p: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).background(Cor.Vermelho))
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
            Text("StreamPLAY ${Atualizador.rotulo(v.versao)} disponível", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
