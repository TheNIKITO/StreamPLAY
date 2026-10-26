package com.thenikito.streamplay.ui

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.thenikito.streamplay.Estado

/**
 * Clique que também funciona com o controle remoto: o item focado cresce um pouco e ganha borda branca.
 * Guarda o último item focado para o foco voltar para ele quando uma janela por cima fecha.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.foco(
    forma: Shape = RoundedCornerShape(6.dp),
    escala: Float = 1.05f,
    borda: Boolean = true,
    focusRequester: FocusRequester? = null,
    aoFocar: ((Boolean) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val inter = remember { MutableInteractionSource() }
    var focado by remember { mutableStateOf(false) }
    val fr = focusRequester ?: remember { FocusRequester() }
    val s by animateFloatAsState(if (focado) escala else 1f, label = "foco")
    LaunchedEffect(inter) {
        inter.interactions.collect {
            val f = when (it) {
                is androidx.compose.foundation.interaction.FocusInteraction.Focus -> true
                is androidx.compose.foundation.interaction.FocusInteraction.Unfocus -> false
                else -> return@collect
            }
            focado = f
            if (f) Estado.ultimoFoco = fr
            aoFocar?.invoke(f)
        }
    }
    // Segurar OK no controle: o controle repete a tecla enquanto ela fica apertada; a repetição vira o "segurar".
    val segurou = remember { booleanArrayOf(false) }
    val segurar = if (onLongClick == null) Modifier else Modifier.onPreviewKeyEvent { ev ->
        val k = ev.nativeKeyEvent
        if (k.keyCode != KeyEvent.KEYCODE_DPAD_CENTER && k.keyCode != KeyEvent.KEYCODE_ENTER && k.keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER)
            return@onPreviewKeyEvent false
        when (ev.type) {
            KeyEventType.KeyDown -> {
                if (k.repeatCount == 0) segurou[0] = false
                else if (!segurou[0]) { segurou[0] = true; onLongClick() }
            }
            KeyEventType.KeyUp -> if (!segurou[0]) onClick()
        }
        true
    }
    Modifier
        .graphicsLayer { scaleX = s; scaleY = s }
        .then(if (focado && borda) Modifier.border(2.5.dp, Color.White, forma) else Modifier)
        .clip(forma)
        .then(segurar)
        .focusRequester(fr)
        .combinedClickable(
            interactionSource = inter,
            indication = LocalIndication.current,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

/** Botão no estilo da Netflix: branco (principal) ou cinza translúcido; com o controle, fica branco quem tem o foco. */
@Composable
fun Botao(
    texto: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icone: ImageVector? = null,
    primario: Boolean = false,
    habilitado: Boolean = true,
    focusRequester: FocusRequester? = null,
    tamanhoTexto: TextUnit = 16.sp,
) {
    var focado by remember { mutableStateOf(false) }
    val controle = usandoControle()
    val claro = focado || (primario && !controle)
    val fundo = if (claro) Color.White else Cor.Cinza
    val frente = if (claro) Color.Black else Color.White
    Row(
        modifier
            .foco(RoundedCornerShape(4.dp), escala = 1.06f, borda = false, focusRequester = focusRequester, aoFocar = { focado = it }) {
                if (habilitado) onClick()
            }
            .background(fundo.copy(alpha = if (habilitado) fundo.alpha else fundo.alpha * 0.5f))
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icone != null) {
            Icon(icone, null, tint = frente, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(texto, color = frente, fontSize = tamanhoTexto, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun IconeBotao(
    icone: ImageVector,
    descricao: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tamanho: Dp = 42.dp,
    fundo: Color = Color.Transparent,
    contorno: Boolean = false,
    focusRequester: FocusRequester? = null,
    onLongClick: (() -> Unit)? = null,
) {
    var focado by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(tamanho)
            .foco(CircleShape, escala = 1.1f, borda = false, focusRequester = focusRequester, aoFocar = { focado = it }, onLongClick = onLongClick, onClick = onClick)
            .background(if (focado) Color.White else fundo)
            .then(if (contorno && !focado) Modifier.border(2.dp, Color(0xFFB3B3B3), CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icone, descricao, tint = if (focado) Color.Black else Color.White, modifier = Modifier.size(tamanho * 0.55f))
    }
}

@Composable
fun Chip(texto: String, selecionado: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, focusRequester: FocusRequester? = null) {
    var focado by remember { mutableStateOf(false) }
    val forma = RoundedCornerShape(50)
    Box(
        modifier
            .foco(forma, escala = 1.06f, borda = false, focusRequester = focusRequester, aoFocar = { focado = it }, onClick = onClick)
            .background(if (selecionado) Color.White else if (focado) Color(0xFF555555) else Color.Transparent)
            .border(1.5.dp, if (focado) Color.White else if (selecionado) Color.White else Color(0xFF808080), forma)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text(texto, color = if (selecionado) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

private val paleta = listOf(
    Color(0xFF8E2E3B), Color(0xFF3E5C9A), Color(0xFF2F7A5B), Color(0xFF7A4FA0),
    Color(0xFFA0662F), Color(0xFF2E7C8E), Color(0xFFA03E6E), Color(0xFF5D6B2F),
)

/** Capa / logotipo. Sem imagem (ou se ela não abrir), mostra o nome sobre um fundo colorido. */
@Composable
fun Imagem(
    url: String?,
    nome: String?,
    modifier: Modifier = Modifier,
    crop: Boolean = true,
    tamanhoNome: TextUnit = 14.sp,
    padding: Dp = 0.dp,
) {
    var falhou by remember(url) { mutableStateOf(url.isNullOrBlank()) }
    val base = paleta[((nome ?: "").hashCode() and 0x7fffffff) % paleta.size]
    Box(
        modifier.background(if (falhou) Brush.linearGradient(listOf(base, Color(0xFF242424))) else Brush.linearGradient(listOf(Cor.Cartao, Cor.Cartao))),
        contentAlignment = Alignment.Center,
    ) {
        if (falhou) {
            Text(
                (nome ?: "").uppercase(), color = Color.White, fontSize = tamanhoNome, fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis, lineHeight = tamanhoNome * 1.1f,
                modifier = Modifier.padding(8.dp),
            )
        } else {
            AsyncImage(
                model = url, contentDescription = nome,
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(padding),
                onError = { falhou = true },
            )
        }
    }
}

@Composable
fun BarraProgresso(fracao: Float, modifier: Modifier = Modifier, altura: Dp = 3.dp) {
    Box(modifier.height(altura).background(Color(0xFF5A5A5A))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fracao.coerceIn(0f, 1f)).background(Cor.Vermelho))
    }
}

@Composable
fun Selo(texto: String, modifier: Modifier = Modifier) {
    Text(
        texto, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape(3.dp)).background(Cor.Vermelho).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
fun Mensagem(titulo: String, texto: String, modifier: Modifier = Modifier) {
    Column(modifier.widthIn(max = 720.dp)) {
        Text(titulo, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        if (texto.isNotEmpty()) Text(texto, fontSize = 15.sp, color = Cor.Sub, lineHeight = 23.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
fun Logo(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Image(
        androidx.compose.ui.res.painterResource(com.thenikito.streamplay.R.drawable.logo), "StreamPLAY",
        modifier = modifier, contentScale = ContentScale.Fit,
    )
}

/** "S  F I L M E" acima do título. */
@Composable
fun RotuloTipo(texto: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("S", color = Cor.Vermelho, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(8.dp))
        Text(texto, color = Color(0xFFD0D0D0), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

fun Modifier.circulo(cor: Color) = this.clip(CircleShape).background(cor)

/** Registra [fr] como destino do foco quando o controle desce da barra de cima. */
@Composable
fun AlvoDoTopo(fr: FocusRequester) {
    androidx.compose.runtime.DisposableEffect(fr) {
        Estado.alvoConteudo = fr
        onDispose { if (Estado.alvoConteudo === fr) Estado.alvoConteudo = null }
    }
}
