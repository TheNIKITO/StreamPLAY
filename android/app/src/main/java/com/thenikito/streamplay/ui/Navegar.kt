package com.thenikito.streamplay.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.thenikito.streamplay.Cartao
import com.thenikito.streamplay.Category
import com.thenikito.streamplay.Estado
import com.thenikito.streamplay.Item
import com.thenikito.streamplay.Kind
import com.thenikito.streamplay.Library
import com.thenikito.streamplay.M3U
import com.thenikito.streamplay.PlayRequest
import com.thenikito.streamplay.Resume
import com.thenikito.streamplay.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

private class Grade(val titulo: String, val itens: List<Item>)

private class Linha(val titulo: String, val cartoes: List<Cartao>, val total: Int, val todos: List<Item>?)

/** Página de Filmes, Séries ou Minha lista (kind = null). */
@Composable
fun TelaNavegar(lib: Library, kind: Kind?) {
    var grade by remember { mutableStateOf<Grade?>(null) }
    var escolherCategoria by remember { mutableStateOf(false) }
    val versao = Store.versao.intValue

    // a busca só filtra depois que a pessoa para de digitar
    var termo by remember { mutableStateOf(Estado.busca) }
    LaunchedEffect(Estado.busca) {
        if (Estado.busca.isBlank()) termo = "" else { delay(260); termo = Estado.busca.trim() }
    }
    var resultados by remember { mutableStateOf<List<Item>?>(null) }
    LaunchedEffect(termo, lib) {
        resultados = null
        if (termo.isNotBlank()) resultados = withContext(Dispatchers.Default) { buscar(lib, kind, termo) }
    }

    BackHandler(Estado.busca.isNotEmpty()) { Estado.limparBusca() }
    BackHandler(grade != null && Estado.busca.isEmpty()) { grade = null }

    val g = grade
    when {
        termo.isNotBlank() -> GradeItens(
            "Resultados para \"$termo\"", resultados ?: emptyList(), voltar = { Estado.limparBusca() },
            vazio = Pair("Nada encontrado para \"$termo\"", "Confira a ortografia ou tente outro nome."), carregando = resultados == null,
        )
        kind == null -> GradeItens(
            "Minha lista", remember(lib, versao) { favoritos(lib, null) }, voltar = null,
            vazio = Pair("Sua lista está vazia", "Abra um filme ou série e toque no botão + (ou segure o cartão) para guardar aqui."),
        )
        g != null -> GradeItens(g.titulo, g.itens, voltar = { grade = null }, vazio = Pair("Nada por aqui", ""))
        else -> Feed(lib, kind, aoCategorias = { escolherCategoria = true }, verTudo = { t, l -> grade = Grade(t, l) })
    }

    if (escolherCategoria && kind != null) {
        DialogoCategorias(lib.cats(kind).filter { it.items.isNotEmpty() }) { c ->
            escolherCategoria = false
            if (c != null) grade = Grade(c.name, c.items)
        }
    }
}

private fun buscar(lib: Library, kind: Kind?, termo: String): List<Item> {
    val q = M3U.norm(termo)
    val pool = if (kind == null) lib.allMovies + lib.allSeries else lib.all(kind)
    val achados = ArrayList<Item>()
    for (i in pool) {
        val n = i.busca ?: M3U.norm(i.name).also { i.busca = it }
        if (n.contains(q)) achados.add(i)
    }
    return achados.sortedBy { if (it.busca!!.startsWith(q)) 0 else 1 }
}

private fun favoritos(lib: Library, kind: Kind?): List<Item> =
    Store.favorites.mapNotNull { lib.get(it) }.filter { it.kind != Kind.Channel && (kind == null || it.kind == kind) }

private fun cartao(i: Item, naFileiraDeLancamentos: Boolean = false) = Cartao(
    i,
    sub = null,
    isNew = i.isRelease && !naFileiraDeLancamentos,
    progress = if (i.kind == Kind.Movie) Store.watched(i.key) else 0.0,
)

fun favoritar(i: Item) {
    val on = Store.toggleFav(i.key)
    Estado.toast(
        if (i.kind == Kind.Channel) (if (on) "${i.name} adicionado aos favoritos" else "${i.name} removido dos favoritos")
        else if (on) "Adicionado à Minha lista" else "Removido da Minha lista",
    )
}

@Composable
private fun topoConteudo() = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + alturaTopo()

// ---------- Feed (destaque + fileiras) ----------

@Composable
private fun Feed(lib: Library, kind: Kind, aoCategorias: () -> Unit, verTudo: (String, List<Item>) -> Unit) {
    val m = medidas()
    val versao = Store.versao.intValue
    val cats = remember(lib, kind) { lib.cats(kind).filter { it.items.isNotEmpty() } }
    val pool = remember(lib, kind) {
        val lanc = cats.filter { it.isRelease }.flatMap { it.items }.distinct()
        var p = (lanc.ifEmpty { lib.all(kind) }).asSequence().filter { !it.logo.isNullOrEmpty() || it.hasBackdrop }.take(15).toList()
        if (p.isEmpty()) p = lib.all(kind).take(15)
        p
    }
    val dinamicas = remember(lib, kind, versao) {
        val l = ArrayList<Linha>()
        val cont = Resume.continueCards(lib, kind)
        if (cont.isNotEmpty()) l.add(Linha("Continuar assistindo", cont, cont.size, null))
        val favs = favoritos(lib, kind)
        if (favs.isNotEmpty()) l.add(Linha("Minha lista", favs.take(40).map { cartao(it) }, favs.size, favs))
        l
    }
    val escopo = rememberCoroutineScope()
    val estado = rememberLazyListState()
    LaunchedEffect(estado) {
        snapshotFlow { if (estado.firstVisibleItemIndex > 0) 1f else (estado.firstVisibleItemScrollOffset / 250f).coerceIn(0f, 1f) }
            .collect { Estado.topoSolido = it }
    }

    if (cats.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(top = topoConteudo() + 60.dp, start = m.margem, end = m.margem)) {
            Mensagem(
                if (kind == Kind.Movie) "Nenhum filme na sua lista ainda" else "Nenhuma série na sua lista ainda",
                if (kind == Kind.Movie)
                    "Adicione filmes no seu arquivo .m3u com group-title=\"FILMES\" (ou \"FILMES | Lançamentos\") — o StreamPLAY atualiza sozinho."
                else
                    "Adicione episódios no seu arquivo .m3u com group-title=\"SERIES\" e o nome no formato \"Nome da Série S01E01\". O StreamPLAY organiza por temporada sozinho.",
            )
        }
        return
    }

    LazyColumn(
        state = estado,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 48.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
    ) {
        // quando o foco volta para o destaque, mostra ele inteiro (título e descrição)
        item(key = "destaque") { Destaque(pool, kind, cats.size > 1, aoCategorias) { escopo.launch { estado.animateScrollToItem(0) } } }
        items(dinamicas, key = { "d:" + it.titulo }) { l -> Fileira(l.titulo, l.cartoes, l.total, l.todos?.let { t -> { verTudo(l.titulo, t) } }) }
        items(cats, key = { "c:" + it.name }) { c ->
            val cartoes = remember(c, versao) { c.items.take(40).map { cartao(it, c.isRelease) } }
            Fileira(c.name, cartoes, c.items.size) { verTudo(c.name, c.items) }
        }
    }
}

@Composable
private fun Destaque(pool: List<Item>, kind: Kind, mostrarCategorias: Boolean, aoCategorias: () -> Unit, aoFocar: () -> Unit) {
    val m = medidas()
    var indice by remember(pool) { mutableIntStateOf(if (pool.isEmpty()) 0 else Random.nextInt(pool.size)) }
    var focado by remember { mutableStateOf(false) }
    val tocar = remember { FocusRequester() }
    val controle = usandoControle()
    AlvoDoTopo(tocar)

    LaunchedEffect(pool) {
        while (true) {
            delay(11_000)
            if (pool.size >= 2 && !focado && Estado.player == null && Estado.detalhes == null) indice = (indice + 1) % pool.size
        }
    }
    LaunchedEffect(Unit) {
        if (controle && Estado.focoInicialPendente) {
            Estado.focoInicialPendente = false
            delay(150)
            try { tocar.requestFocus() } catch (_: Exception) { }
        }
    }

    val item = pool.getOrNull(indice) ?: return
    val altura = if (m.largo) min(m.larguraTela * 0.5f, m.alturaTela * 0.78f).coerceIn(260.dp, 640.dp)
    else (m.larguraTela * 1.3f).coerceIn(420.dp, 620.dp)
    val versao = Store.versao.intValue

    Box(Modifier.fillMaxWidth().height(altura).onFocusChanged { if (it.hasFocus && !focado) aoFocar(); focado = it.hasFocus }.focusGroup()) {
        Crossfade(item, animationSpec = tween(700), label = "destaque") { FundoDestaque(it, altura) }
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Color(0xCC141414), 0.65f to Color.Transparent)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Cor.Fundo)))

        Row(
            Modifier.statusBarsPadding().padding(top = alturaTopo(), start = m.margem),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (kind == Kind.Movie) "Filmes" else "Séries", fontSize = if (m.largo) 30.sp else 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            if (mostrarCategorias) {
                Spacer(Modifier.width(18.dp))
                var f by remember { mutableStateOf(false) }
                Row(
                    Modifier
                        .foco(RoundedCornerShape(2.dp), borda = false, aoFocar = { f = it }, onClick = aoCategorias)
                        .background(if (f) Color.White else Color.Black)
                        .border(1.dp, Color.White, RoundedCornerShape(2.dp))
                        .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Categorias", color = if (f) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Filled.KeyboardArrowDown, null, tint = if (f) Color.Black else Color.White)
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = m.margem, end = m.margem, bottom = 20.dp)
                .widthIn(max = 640.dp),
        ) {
            RotuloTipo(item.kindLabel)
            Text(
                item.name, color = Color.White, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontSize = if (m.largo) 42.sp else 30.sp, lineHeight = if (m.largo) 46.sp else 34.sp, shadow = Shadow(Color(0x99000000), blurRadius = 12f)),
                modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.isRelease) Selo("LANÇAMENTO", Modifier.padding(end = 12.dp))
                Text(item.meta, color = Color(0xFFE0E0E0), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!item.plot.isNullOrBlank()) Text(
                item.plot!!, color = Color.White, fontSize = if (m.largo) 16.sp else 14.sp, lineHeight = if (m.largo) 23.sp else 20.sp,
                maxLines = if (m.largo) 3 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp),
            )
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Botao(textoAssistir(item, versao), { Estado.tocar(PlayRequest.forItem(item)) }, icone = Icons.Filled.PlayArrow, primario = true, focusRequester = tocar)
                Botao(if (m.largo) "Mais informações" else "Detalhes", { Estado.abrirDetalhes(item) }, icone = Icons.Filled.Info)
            }
        }
    }
}

@Suppress("UNUSED_PARAMETER")
private fun textoAssistir(item: Item, versao: Int): String {
    if (item.kind == Kind.Movie) return if (Store.watched(item.key) > 0) "Continuar" else "Assistir"
    val ep = Resume.episodeFor(item) ?: return "Assistir"
    return if (Store.getProgress(ep.key) != null || ep != item.episodes.firstOrNull()) "Continuar ${ep.label}" else "Assistir"
}

@Composable
fun FundoDestaque(item: Item, altura: androidx.compose.ui.unit.Dp) {
    val m = medidas()
    Box(Modifier.fillMaxSize()) {
        if (item.hasBackdrop) {
            Imagem(item.backdrop, item.name, Modifier.fillMaxSize())
        } else {
            // sem imagem larga: a capa desfocada no fundo e a capa inteira do lado
            Imagem(
                item.logo, item.name,
                Modifier.fillMaxSize().alpha(0.45f).then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(28.dp) else Modifier),
                tamanhoNome = 1.sp,
            )
            if (m.largo) Imagem(
                item.logo, item.name,
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = m.margem * 2, top = 40.dp)
                    .height(altura * 0.72f)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(6.dp)),
                tamanhoNome = 22.sp,
            )
        }
    }
}

// ---------- Fileiras ----------

@Composable
private fun Fileira(titulo: String, cartoes: List<Cartao>, total: Int, verTudo: (() -> Unit)?) {
    val m = medidas()
    Column(Modifier.padding(top = 14.dp)) {
        Row(Modifier.padding(horizontal = m.margem), verticalAlignment = Alignment.Bottom) {
            Text(titulo, color = Cor.Texto, fontSize = if (m.largo) 20.sp else 17.sp, fontWeight = FontWeight.SemiBold)
            if (verTudo != null && total > 6) {
                Spacer(Modifier.width(12.dp))
                var f by remember { mutableStateOf(false) }
                Text(
                    "Ver tudo ›", color = if (f) Color.Black else Cor.Link, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .foco(RoundedCornerShape(4.dp), borda = false, aoFocar = { f = it }, onClick = verTudo)
                        .background(if (f) Color.White else Color.Transparent)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = m.margem, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(cartoes, key = { it.item.key + "|" + (it.ep?.key ?: "") }) { CartaoPoster(it, Modifier.width(m.cartao)) }
            if (verTudo != null && total > cartoes.size) item(key = "tudo") {
                var f by remember { mutableStateOf(false) }
                Box(
                    Modifier
                        .width(m.cartao)
                        .aspectRatio(2f / 3f)
                        .foco(RoundedCornerShape(4.dp), aoFocar = { f = it }, onClick = verTudo)
                        .background(if (f) Color(0xFF3A3A3A) else Cor.Cartao),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Ver todos\n($total)", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

@Composable
fun CartaoPoster(c: Cartao, modifier: Modifier = Modifier, focusRequester: FocusRequester? = null) {
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .foco(RoundedCornerShape(4.dp), focusRequester = focusRequester, onLongClick = { favoritar(c.item) }) { Estado.abrirCartao(c) },
        ) {
            Imagem(c.item.logo, c.item.name, Modifier.fillMaxSize())
            if (c.isNew) Selo("NOVO", Modifier.align(Alignment.TopStart).padding(6.dp))
            if (c.hasProgress) BarraProgresso(c.progress.toFloat(), Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
        Text(
            c.sub ?: c.item.name, color = Cor.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp, start = 2.dp, end = 2.dp),
        )
    }
}

// ---------- Grade (categoria, busca, Minha lista) ----------

@Composable
private fun GradeItens(titulo: String, itens: List<Item>, voltar: (() -> Unit)?, vazio: Pair<String, String>, carregando: Boolean = false) {
    val m = medidas()
    val versao = Store.versao.intValue
    val estado = rememberLazyGridState()
    LaunchedEffect(Unit) { Estado.topoSolido = 1f }
    val primeiro = remember { FocusRequester() }
    val voltarFr = remember { FocusRequester() }
    AlvoDoTopo(if (itens.isEmpty() && voltar != null) voltarFr else primeiro)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(m.cartao),
        state = estado,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = m.margem, end = m.margem, top = topoConteudo() + 8.dp,
            bottom = 48.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "titulo") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                if (voltar != null) {
                    IconeBotao(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", voltar, focusRequester = voltarFr)
                    Spacer(Modifier.width(8.dp))
                }
                Text(titulo, fontSize = if (m.largo) 28.sp else 22.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                if (itens.isNotEmpty()) Text(
                    "  ${itens.size} ${if (itens.size == 1) "título" else "títulos"}", color = Cor.Dim, fontSize = 14.sp,
                )
            }
        }
        if (carregando) item(span = { GridItemSpan(maxLineSpan) }, key = "carregando") {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Cor.Vermelho) }
        } else if (itens.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "vazio") {
            Mensagem(vazio.first, vazio.second, Modifier.padding(top = 40.dp))
        }
        itemsIndexed(itens, key = { _, it -> it.key }) { i, it -> CartaoPoster(remember(it, versao) { cartao(it) }, focusRequester = if (i == 0) primeiro else null) }
    }
}

@Composable
private fun DialogoCategorias(cats: List<Category>, escolher: (Category?) -> Unit) {
    val primeiro = remember { FocusRequester() }
    val controle = usandoControle()
    Dialog(onDismissRequest = { escolher(null) }) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xF0181818))
                .border(1.dp, Cor.Linha, RoundedCornerShape(8.dp))
                .padding(vertical = 12.dp),
        ) {
            Text("Categorias", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            LazyColumn {
                items(cats.size) { i ->
                    val c = cats[i]
                    var f by remember { mutableStateOf(false) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .foco(RoundedCornerShape(0.dp), escala = 1f, borda = false, focusRequester = if (i == 0) primeiro else null, aoFocar = { f = it }) { escolher(c) }
                            .background(if (f) Color.White else Color.Transparent)
                            .padding(horizontal = 20.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(c.name, color = if (f) Color.Black else Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.items.size.toString(), color = if (f) Color.DarkGray else Cor.Dim, fontSize = 13.sp)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { if (controle) { delay(120); try { primeiro.requestFocus() } catch (_: Exception) { } } }
}
