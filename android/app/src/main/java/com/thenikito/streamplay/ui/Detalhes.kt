package com.thenikito.streamplay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thenikito.streamplay.Episode
import com.thenikito.streamplay.Estado
import com.thenikito.streamplay.Item
import com.thenikito.streamplay.Kind
import com.thenikito.streamplay.PlayRequest
import com.thenikito.streamplay.Progresso
import com.thenikito.streamplay.Resume
import com.thenikito.streamplay.Store
import com.thenikito.streamplay.faltam
import com.thenikito.streamplay.tempo
import kotlinx.coroutines.delay

/** Janela "Mais informações" (filme ou série com temporadas e episódios). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TelaDetalhes(item: Item) {
    val m = medidas()
    val versao = Store.versao.intValue
    val tocar = remember { FocusRequester() }
    val controle = usandoControle()
    BackHandler { Estado.fecharDetalhes() }
    LaunchedEffect(Unit) { if (controle) { delay(120); try { tocar.requestFocus() } catch (_: Exception) { } } }

    // o que o botão principal faz (igual ao PC)
    var progresso: Progresso? = null
    val textoTocar: String
    val atualEp: Episode?
    if (item.kind == Kind.Movie) {
        atualEp = null
        progresso = Store.getProgress(item.key)?.takeIf { !it.finished && it.fraction >= 0.01 }
        textoTocar = if (progresso != null) "Continuar" else "Assistir"
    } else {
        atualEp = Resume.episodeFor(item)
        if (atualEp != null) {
            progresso = Store.getProgress(atualEp.key)?.takeIf { !it.finished && it.fraction >= 0.01 }
            val comecou = progresso != null || atualEp != item.episodes.first()
            textoTocar = (if (comecou) "Continuar " else "Assistir ") + atualEp.label
        } else textoTocar = "Assistir"
    }
    // temporada escolhida nos chips; antes de escolher, a do episódio atual
    val temporada = if (Estado.temporada != 0) Estado.temporada else atualEp?.season ?: 1
    val eps = remember(item, temporada) { item.episodes.filter { it.season == temporada } }
    val fav = Store.isFav(item.key) && versao >= 0

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.matchParentSize().bloqueiaToques { Estado.fecharDetalhes() })
        val cheia = !m.largo
        val topo = if (cheia) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 24.dp
        LazyColumn(
            Modifier
                .padding(top = topo)
                .widthIn(max = 900.dp)
                .fillMaxWidth(if (cheia) 1f else 0.92f)
                .clip(RoundedCornerShape(if (cheia) 0.dp else 8.dp))
                .background(Color(0xFF181818)),
            contentPadding = PaddingValues(bottom = 40.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        ) {
            item(key = "topo") {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val alt = (maxWidth * 0.56f).coerceIn(260.dp, 460.dp)
                    Box(Modifier.fillMaxWidth().height(alt)) {
                        FundoDestaque(item, alt)
                        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xFF181818))))
                        IconeBotao(
                            Icons.Filled.Close, "Fechar", { Estado.fecharDetalhes() },
                            Modifier.align(Alignment.TopEnd).padding(top = if (cheia) WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp else 14.dp, end = 14.dp),
                            fundo = Color(0xCC181818),
                        )
                        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 24.dp, vertical = 14.dp)) {
                            RotuloTipo(item.kindLabel)
                            Text(
                                item.name, color = Color.White, fontSize = if (m.largo) 34.sp else 26.sp, lineHeight = if (m.largo) 38.sp else 30.sp,
                                fontWeight = FontWeight.Black, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Botao(textoTocar, {
                                    if (item.kind == Kind.Movie) Estado.tocar(PlayRequest.forItem(item))
                                    else atualEp?.let { Estado.tocar(PlayRequest.forEpisode(it)) }
                                }, icone = Icons.Filled.PlayArrow, primario = true, focusRequester = tocar)
                                if (progresso != null) IconeBotao(Icons.Filled.Refresh, "Assistir do início", {
                                    val req = if (item.kind == Kind.Movie) PlayRequest.forItem(item) else PlayRequest.forEpisode(atualEp)
                                    if (req != null) { req.fromStart = true; Estado.tocar(req) }
                                }, contorno = true, fundo = Color(0x66000000))
                                IconeBotao(
                                    if (fav) Icons.Filled.Check else Icons.Filled.Add,
                                    if (fav) "Remover da Minha lista" else "Adicionar à Minha lista",
                                    { favoritar(item) }, contorno = true, fundo = Color(0x66000000),
                                )
                            }
                        }
                    }
                }
            }

            item(key = "info") {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    val p = progresso
                    if (p != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                        BarraProgresso(p.fraction.toFloat(), Modifier.width(200.dp))
                        Text(faltam(p.dur - p.pos), color = Cor.Sub, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item.isRelease) Selo("LANÇAMENTO", Modifier.padding(end = 12.dp))
                        if (!item.year.isNullOrEmpty()) Meta(item.year!!, Color(0xFFBCBCBC))
                        if (!item.rating.isNullOrEmpty()) Meta("★ " + item.rating, Cor.Verde)
                        if (item.kind == Kind.Series) Meta(item.countText, Color(0xFFBCBCBC))
                        Text(
                            "HD", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.border(1.dp, Color(0xFF808080), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                    val plot = item.plot ?: if (item.kind == Kind.Series) "Escolha a temporada e o episódio abaixo." else null
                    if (!plot.isNullOrBlank()) Text(plot, color = Color.White, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 12.dp))
                    if (!item.genre.isNullOrEmpty()) Rotulado("Gênero: ", item.genre!!)
                    if (item.categories.isNotEmpty()) Rotulado(if (item.categories.size > 1) "Categorias: " else "Categoria: ", item.categories.joinToString(", "))
                }
            }

            if (item.kind == Kind.Series) {
                item(key = "eps") {
                    val seasons = item.seasons
                    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 26.dp, bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("Episódios", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            if (seasons.size <= 1) Text("   Temporada ${seasons.firstOrNull() ?: 1}", color = Cor.Sub, fontSize = 15.sp)
                        }
                    }
                    if (seasons.size > 1) LazyRow(
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(seasons) { s -> Chip("Temporada $s", s == temporada, { Estado.temporada = s }) }
                    }
                }
                items(eps, key = { it.key }) { ep -> LinhaEpisodio(ep, ep == atualEp, versao) }
            }
        }
    }
}

@Composable
private fun Meta(texto: String, cor: Color) {
    Text(texto, color = cor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 12.dp))
}

@Composable
private fun Rotulado(rotulo: String, valor: String) {
    Text(
        buildAnnotatedString {
            append(rotulo)
            withStyle(SpanStyle(color = Color.White)) { append(valor) }
        },
        color = Cor.Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
    )
}

@Suppress("UNUSED_PARAMETER")
@Composable
private fun LinhaEpisodio(ep: Episode, atual: Boolean, versao: Int) {
    val m = medidas()
    val p = Store.getProgress(ep.key)
    var f by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .foco(RoundedCornerShape(6.dp), escala = 1.02f, aoFocar = { f = it }) { Estado.tocar(PlayRequest.forEpisode(ep)) }
            .background(if (f) Color(0xFF3A3A3A) else if (atual) Color(0xFF2E2E2E) else Color.Transparent)
            .padding(vertical = 12.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(ep.number.toString(), color = Color(0xFFD2D2D2), fontSize = 22.sp, modifier = Modifier.width(40.dp))
        Box(Modifier.width(if (m.largo) 150.dp else 112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp))) {
            Imagem(ep.image, ep.show.name, Modifier.fillMaxSize(), tamanhoNome = 10.sp)
            if (p != null && !p.finished && p.fraction > 0.01)
                BarraProgresso(p.fraction.toFloat(), Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ep.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (p != null && p.dur > 0) Text(if (p.finished) "Assistido" else tempo(p.dur), color = Cor.Sub, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
            }
            if (!ep.plot.isNullOrEmpty()) Text(
                ep.plot, color = Cor.Sub, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
