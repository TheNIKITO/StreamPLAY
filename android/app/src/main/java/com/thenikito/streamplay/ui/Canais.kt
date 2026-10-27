package com.thenikito.streamplay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thenikito.streamplay.Estado
import com.thenikito.streamplay.Guia
import com.thenikito.streamplay.Item
import com.thenikito.streamplay.Kind
import com.thenikito.streamplay.Library
import com.thenikito.streamplay.M3U
import com.thenikito.streamplay.PlayRequest
import com.thenikito.streamplay.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private class CatCanal(val key: String, val nome: String, val itens: List<Item>)

@Composable
fun TelaCanais(lib: Library) {
    val m = medidas()
    val versao = Store.versao.intValue
    var sel by rememberSaveable { mutableStateOf("all") }

    val cats = remember(lib, versao) {
        val l = ArrayList<CatCanal>()
        val favs = Store.favorites.mapNotNull { lib.get(it) }.filter { it.kind == Kind.Channel }
        val recentes = Store.recent.mapNotNull { lib.get(it) }.filter { it.kind == Kind.Channel }
        if (favs.isNotEmpty()) l.add(CatCanal("fav", "★  Favoritos", favs))
        if (recentes.isNotEmpty()) l.add(CatCanal("recent", "Vistos recentemente", recentes))
        l.add(CatCanal("all", "Todos os canais", lib.allChannels))
        for (c in lib.channels) if (c.items.isNotEmpty()) l.add(CatCanal("c:" + c.name, c.name, c.items))
        l
    }
    val atual = cats.firstOrNull { it.key == sel } ?: cats.first { it.key == "all" }

    var termo by remember { mutableStateOf(Estado.busca) }
    LaunchedEffect(Estado.busca) {
        if (Estado.busca.isBlank()) termo = "" else { delay(260); termo = Estado.busca.trim() }
    }
    var achados by remember { mutableStateOf<List<Item>?>(null) }
    LaunchedEffect(termo, lib) {
        achados = null
        if (termo.isNotBlank()) achados = withContext(Dispatchers.Default) {
            val q = M3U.norm(termo)
            lib.allChannels.filter { (it.busca ?: M3U.norm(it.name).also { n -> it.busca = n }).contains(q) }
                .sortedBy { if (it.busca!!.startsWith(q)) 0 else 1 }
        }
    }
    BackHandler(Estado.busca.isNotEmpty()) { Estado.limparBusca() }

    val buscando = termo.isNotBlank()
    val lista = if (buscando) achados ?: emptyList() else atual.itens
    val titulo = if (buscando) "Resultados para \"$termo\"" else atual.nome.removePrefix("★  ")
    val topo = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + alturaTopo()
    val primeiro = remember { FocusRequester() }
    val controle = usandoControle()
    val primeiroCanal = remember { FocusRequester() }
    AlvoDoTopo(if (termo.isNotBlank()) primeiroCanal else primeiro)
    LaunchedEffect(Unit) {
        if (controle && Estado.focoInicialPendente) {
            Estado.focoInicialPendente = false
            delay(150)
            try { primeiro.requestFocus() } catch (_: Exception) { }
        }
    }

    if (m.largo) {
        Row(Modifier.fillMaxSize().padding(top = topo)) {
            Column(Modifier.width(if (m.tv) 270.dp else 240.dp).fillMaxHeight().background(Color(0xFF0E0E0E))) {
                Text("Canais", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(start = 24.dp, top = 16.dp))
                Text(
                    if (lib.allChannels.size == 1) "1 canal" else "${lib.allChannels.size} canais", color = Cor.Dim, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 24.dp, bottom = 10.dp),
                )
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(cats, key = { it.key }) { c ->
                        ItemCategoria(c, c.key == sel && !buscando, if (c.key == "all") primeiro else null) { sel = c.key; Estado.limparBusca() }
                    }
                }
            }
            GradeCanais(titulo, lista, buscando, lib, Modifier.weight(1f), m.margem / 2 + 8.dp, primeiroCanal)
        }
    } else {
        Column(Modifier.fillMaxSize().padding(top = topo)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = m.margem, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(cats, key = { it.key }) { c ->
                    Chip("${c.nome.removePrefix("★  ")} (${c.itens.size})", c.key == sel && !buscando, { sel = c.key; Estado.limparBusca() },
                        focusRequester = if (c.key == "all") primeiro else null)
                }
            }
            GradeCanais(titulo, lista, buscando, lib, Modifier.weight(1f), m.margem, primeiroCanal)
        }
    }
}

@Composable
private fun ItemCategoria(c: CatCanal, selecionada: Boolean, fr: FocusRequester?, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .foco(RoundedCornerShape(0.dp), escala = 1f, borda = false, focusRequester = fr, aoFocar = { f = it }, onClick = onClick)
            .background(if (f) Color.White else if (selecionada) Color(0xFF232323) else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).size(4.dp, 40.dp).background(if (selecionada) Cor.Vermelho else Color.Transparent))
        Text(
            c.nome, color = if (f) Color.Black else if (selecionada) Color.White else Cor.Sub, fontSize = 15.sp,
            fontWeight = if (selecionada) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 20.dp, top = 11.dp, bottom = 11.dp),
        )
        Text(c.itens.size.toString(), color = if (f) Color.DarkGray else Cor.Dim, fontSize = 12.sp, modifier = Modifier.padding(end = 18.dp))
    }
}

@Composable
private fun GradeCanais(titulo: String, lista: List<Item>, buscando: Boolean, lib: Library, modifier: Modifier, margem: androidx.compose.ui.unit.Dp, primeiroCanal: FocusRequester) {
    val m = medidas()
    val versao = Store.versao.intValue
    // guia de programação: "Agora / A seguir" nos cartões, redesenhado a cada meio minuto
    val comGuia = Guia.versao.intValue >= 0 && Guia.temAlgum
    val agora = if (comGuia) lembrarAgora(30_000) else 0L
    val estado = rememberLazyGridState()
    LaunchedEffect(titulo) { estado.scrollToItem(0) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(m.canal),
        state = estado,
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(
            start = margem, end = m.margem, top = 12.dp,
            bottom = 40.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "titulo") {
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(titulo, fontSize = if (m.largo) 24.sp else 20.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                    Text("   ${if (lista.size == 1) "1 canal" else "${lista.size} canais"}", color = Cor.Dim, fontSize = 13.sp)
                }
                Text(
                    if (m.tv) "Segure OK num canal para favoritar." else "Toque e segure num canal para favoritar.",
                    color = Cor.Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (lista.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "vazio") {
            when {
                buscando -> Mensagem("Nenhum canal encontrado", "Tente outro nome.")
                lib.allChannels.isEmpty() -> Mensagem(
                    "Nenhum canal na sua lista ainda",
                    "Adicione canais no seu arquivo .m3u com group-title=\"CANAIS | Esportes\" (ou só o nome do grupo, ex.: \"Esportes\").",
                )
                else -> Mensagem("Nada por aqui", "")
            }
        }
        itemsIndexed(lista, key = { _, it -> it.key }) { i, ch ->
            CartaoCanal(ch, Store.isFav(ch.key) && versao >= 0, if (comGuia) agora else null, if (i == 0) primeiroCanal else null) {
                Estado.tocar(PlayRequest.forChannel(ch, lista))
            }
        }
    }
}

@Composable
private fun CartaoCanal(ch: Item, favorito: Boolean, agora: Long?, fr: FocusRequester?, tocar: () -> Unit) {
    Column(
        Modifier
            .foco(RoundedCornerShape(6.dp), focusRequester = fr, onLongClick = { favoritar(ch) }, onClick = tocar)
            .background(Color(0xFF1C1C1C)),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            Imagem(ch.logo, ch.name, Modifier.fillMaxSize(), crop = false, tamanhoNome = 16.sp, padding = 12.dp)
            if (favorito) Icon(Icons.Filled.Star, "Favorito", tint = Color(0xFFFFC83D), modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp))
        }
        Text(
            ch.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp).padding(top = 9.dp, bottom = if (agora == null) 9.dp else 3.dp),
        )
        // com guia na lista, todos os cartões reservam as linhas (a grade fica alinhada mesmo sem programação)
        if (agora != null) {
            val (a, s) = Guia.agoraESeguir(ch, agora)
            Column(Modifier.padding(horizontal = 10.dp).padding(bottom = 9.dp)) {
                Text(
                    a?.titulo ?: s?.let { "Às ${Guia.hhmm(it.inicio)}: ${it.titulo}" } ?: " ",
                    color = Cor.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(3.dp).background(if (a != null) Color(0xFF4A4A4A) else Color.Transparent)) {
                    if (a != null) Box(Modifier.fillMaxWidth(a.progresso(agora)).fillMaxHeight().background(Cor.Vermelho))
                }
                Text(
                    if (a != null && s != null) "A seguir ${Guia.hhmm(s.inicio)}: ${s.titulo}" else " ",
                    color = Cor.Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** A hora atual, renovada a cada [intervalo] ms enquanto a tela estiver aberta. */
@Composable
internal fun lembrarAgora(intervalo: Long): Long {
    val t = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(intervalo) {
        while (true) {
            delay(intervalo - System.currentTimeMillis() % intervalo)
            t.longValue = System.currentTimeMillis()
        }
    }
    return t.longValue
}
