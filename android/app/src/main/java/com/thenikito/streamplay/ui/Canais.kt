package com.thenikito.streamplay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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

/**
 * Aba Canais: os grupos (Todos, Favoritos, Esportes...) ficam em cima; os canais do grupo numa lista com rolagem.
 * Na TV e no tablet, à direita, o painel do canal escolhido (o que está passando, os próximos, Assistir, Favoritar):
 * na TV o painel acompanha o canal focado e OK assiste; no toque, o 1º toque escolhe e o 2º assiste.
 * No celular é só a lista: tocar assiste, segurar favorita.
 */
@Composable
fun TelaCanais(lib: Library) {
    val m = medidas()
    val versao = Store.versao.intValue
    var sel by rememberSaveable { mutableStateOf("all") }
    var escolhido by rememberSaveable { mutableStateOf<String?>(null) }

    val cats = remember(lib, versao) {
        val l = ArrayList<CatCanal>()
        l.add(CatCanal("all", "Todos", lib.allChannels))
        val favs = Store.favorites.mapNotNull { lib.get(it) }.filter { it.kind == Kind.Channel }
        val recentes = Store.recent.mapNotNull { lib.get(it) }.filter { it.kind == Kind.Channel }
        if (favs.isNotEmpty()) l.add(CatCanal("fav", "★ Favoritos", favs))
        if (recentes.isNotEmpty()) l.add(CatCanal("recent", "Recentes", recentes))
        for (c in lib.channels) if (c.items.isNotEmpty()) l.add(CatCanal("c:" + c.name, c.name, c.items))
        l
    }
    val atual = cats.firstOrNull { it.key == sel } ?: cats.first()
    // número fixo de cada canal (a ordem de "Todos"), igual em todos os grupos
    val numeros = remember(lib) { HashMap<String, Int>().also { n -> lib.allChannels.forEachIndexed { i, c -> n[c.key] = i + 1 } } }

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
    val titulo = if (buscando) "Resultados para \"$termo\"" else if (atual.key == "all") "Canais" else atual.nome.removePrefix("★ ")
    val canal = lista.firstOrNull { it.key == escolhido }
        ?: Store.recent.firstNotNullOfOrNull { k -> lista.firstOrNull { it.key == k } }
        ?: lista.firstOrNull()

    val topo = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + alturaTopo()
    val primeiro = remember { FocusRequester() }
    val primeiroCanal = remember { FocusRequester() }
    val controle = usandoControle()
    AlvoDoTopo(if (buscando) primeiroCanal else primeiro)
    LaunchedEffect(Unit) {
        if (controle && Estado.focoInicialPendente) {
            Estado.focoInicialPendente = false
            delay(150)
            try { primeiro.requestFocus() } catch (_: Exception) { }
        }
    }

    fun tocar(ch: Item) = Estado.tocar(PlayRequest.forChannel(ch, lista))

    Column(Modifier.fillMaxSize().padding(top = topo)) {
        // título + grupos
        Row(Modifier.padding(start = m.margem, end = m.margem, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(4.dp, 22.dp).clip(RoundedCornerShape(2.dp)).background(Cor.Vermelho))
            Spacer(Modifier.width(10.dp))
            Text(titulo, fontSize = if (m.largo) 24.sp else 20.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
            Text("   ${if (lista.size == 1) "1 canal" else "${lista.size} canais"}", color = Cor.Dim, fontSize = 13.sp)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = m.margem, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(cats, key = { it.key }) { c ->
                ChipGrupo("${c.nome}  ${c.itens.size}", c.key == sel && !buscando, if (c.key == "all") primeiro else null) {
                    sel = c.key
                    Estado.limparBusca()
                }
            }
        }

        if (lista.isEmpty()) {
            Box(Modifier.padding(horizontal = m.margem, vertical = 20.dp)) {
                when {
                    buscando -> Mensagem("Nenhum canal encontrado", "Tente outro nome.")
                    lib.allChannels.isEmpty() -> Mensagem(
                        "Nenhum canal na sua lista ainda",
                        "Adicione canais no seu arquivo .m3u com group-title=\"CANAIS | Esportes\" (ou só o nome do grupo, ex.: \"Esportes\").",
                    )
                    else -> Mensagem("Nada por aqui", "")
                }
            }
            return@Column
        }

        val comGuia = Guia.versao.intValue >= 0 && Guia.temAlgum
        val agora = if (comGuia) lembrarAgora(30_000) else 0L
        if (m.largo) {
            Row(Modifier.fillMaxSize().padding(start = m.margem - 8.dp, end = m.margem)) {
                ListaCanais(
                    lista, numeros, canal?.key, if (comGuia) agora else null, primeiroCanal,
                    Modifier.width(if (m.tv) 400.dp else 360.dp).fillMaxHeight(),
                    aoFocar = { escolhido = it.key },
                ) { ch -> if (controle || ch.key == canal?.key) tocar(ch) else escolhido = ch.key }
                if (canal != null) PainelCanal(canal, if (comGuia) agora else null, Modifier.weight(1f).padding(start = 28.dp)) { tocar(canal) }
            }
        } else {
            ListaCanais(lista, numeros, null, if (comGuia) agora else null, primeiroCanal, Modifier.fillMaxSize().padding(horizontal = m.margem - 8.dp)) { tocar(it) }
        }
    }
}

@Composable
private fun ChipGrupo(texto: String, selecionado: Boolean, fr: FocusRequester?, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val forma = RoundedCornerShape(50)
    Text(
        texto, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        color = if (f) Color.Black else if (selecionado) Color.White else Color(0xFFD8D8E0),
        modifier = Modifier
            .foco(forma, escala = 1.06f, borda = false, focusRequester = fr, aoFocar = { f = it }, onClick = onClick)
            .background(if (f) Color.White else if (selecionado) Cor.Vermelho else Cor.Cartao)
            .border(1.dp, if (f || selecionado) Color.Transparent else Color(0xFF2E2E38), forma)
            .padding(horizontal = 15.dp, vertical = 7.dp),
    )
}

@Composable
private fun ListaCanais(
    lista: List<Item>,
    numeros: Map<String, Int>,
    selecionado: String?,
    agora: Long?,
    primeiroCanal: FocusRequester,
    modifier: Modifier,
    aoFocar: ((Item) -> Unit)? = null,
    tocar: (Item) -> Unit,
) {
    val versao = Store.versao.intValue
    val estado = rememberLazyListState()
    LaunchedEffect(lista) { estado.scrollToItem(0) }
    LazyColumn(
        state = estado,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF121217))
            .border(1.dp, Color(0xFF1F1F27), RoundedCornerShape(12.dp)),
        contentPadding = PaddingValues(top = 6.dp, bottom = 6.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
    ) {
        itemsIndexed(lista, key = { _, it -> it.key }) { i, ch ->
            LinhaCanal(
                ch, numeros[ch.key] ?: (i + 1), ch.key == selecionado, Store.isFav(ch.key) && versao >= 0, agora,
                if (i == 0) primeiroCanal else null, aoFocar,
            ) { tocar(ch) }
        }
    }
}

@Composable
private fun LinhaCanal(
    ch: Item, numero: Int, selecionado: Boolean, favorito: Boolean, agora: Long?, fr: FocusRequester?,
    aoFocar: ((Item) -> Unit)?, onClick: () -> Unit,
) {
    var f by remember { mutableStateOf(false) }
    val texto = if (f) Color.Black else Color.White
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .foco(RoundedCornerShape(8.dp), escala = 1.02f, borda = false, focusRequester = fr,
                aoFocar = { f = it; if (it) aoFocar?.invoke(ch) }, onLongClick = { favoritar(ch) }, onClick = onClick)
            .background(if (f) Color.White else if (selecionado) Color(0xFF1E1E26) else Color.Transparent)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(3.dp, 36.dp).clip(RoundedCornerShape(2.dp)).background(if (selecionado && !f) Cor.Vermelho else Color.Transparent))
        Text("$numero", color = if (f) Color.DarkGray else Color(0xFF6E6E78), fontSize = 13.sp, modifier = Modifier.width(34.dp).padding(start = 8.dp))
        Imagem(
            ch.logo, ch.name, Modifier.size(62.dp, 36.dp).clip(RoundedCornerShape(6.dp)).background(Cor.Cartao),
            crop = false, tamanhoNome = 9.sp, padding = 4.dp,
        )
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(ch.name, color = texto, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (agora != null) {
                val (a, s) = Guia.agoraESeguir(ch, agora)
                val linha = a?.titulo ?: s?.let { "Às ${Guia.hhmm(it.inicio)}: ${it.titulo}" }
                if (linha != null) {
                    Text(linha, color = if (f) Color.DarkGray else Cor.Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp))
                    if (a != null) BarraProgresso(a.progresso(agora), Modifier.padding(top = 5.dp).fillMaxWidth(), altura = 2.dp)
                }
            }
        }
        if (favorito) Icon(Icons.Filled.Star, "Favorito", tint = Color(0xFFFFC83D), modifier = Modifier.padding(end = 10.dp).size(16.dp))
    }
}

/** Painel do canal escolhido (TV e tablet): logo, AO VIVO, agora, a seguir, Assistir e Favoritar. */
@Composable
private fun PainelCanal(ch: Item, agora: Long?, modifier: Modifier, tocar: () -> Unit) {
    val versao = Store.versao.intValue
    val favorito = Store.isFav(ch.key) && versao >= 0
    Column(modifier.fillMaxHeight().verticalScroll(rememberScrollState()).widthIn(max = 720.dp)) {
        Imagem(
            ch.logo, ch.name,
            Modifier.size(260.dp, 146.dp).clip(RoundedCornerShape(12.dp)).background(Cor.Painel).border(1.dp, Color(0xFF26262E), RoundedCornerShape(12.dp)),
            crop = false, tamanhoNome = 22.sp, padding = 20.dp,
        )
        Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clip(RoundedCornerShape(4.dp)).background(Cor.Vermelho).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White))
                Text("  AO VIVO", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            if (ch.category.isNotEmpty()) Text("   ${ch.category}", color = Cor.Sub, fontSize = 14.sp)
        }
        Text(ch.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp, modifier = Modifier.padding(top = 8.dp))

        val (a, s) = if (agora != null) Guia.agoraESeguir(ch, agora) else Pair(null, null)
        if (a != null || s != null) {
            Text("AGORA", color = Cor.Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
            Text(a?.titulo ?: "Sem programa agora", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))
            if (a != null && agora != null) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BarraProgresso(a.progresso(agora), Modifier.width(280.dp), altura = 4.dp)
                Text("   ${a.horario}", color = Cor.Sub, fontSize = 13.sp)
            }
            a?.descricao?.let {
                Text(it, color = Color(0xFFC8C8D0), fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            }
            val proximos = Guia.proximos(ch, agora ?: 0L, 5)
            if (proximos.isNotEmpty()) {
                Text("A SEGUIR", color = Cor.Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                for (p in proximos) Row(Modifier.padding(vertical = 3.dp)) {
                    Text(Guia.hhmm(p.inicio), color = Cor.Sub, fontSize = 14.sp, modifier = Modifier.width(56.dp))
                    Text(p.titulo, color = Color(0xFFE4E4EA), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Text("Sem programação disponível para este canal.", color = Cor.Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp))
        }
        Row(Modifier.padding(top = 22.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Botao("Assistir", tocar, icone = Icons.Filled.PlayArrow, primario = true)
            Botao(if (favorito) "Favorito" else "Favoritar", { favoritar(ch) }, icone = if (favorito) Icons.Filled.Star else Icones.EstrelaContorno)
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
