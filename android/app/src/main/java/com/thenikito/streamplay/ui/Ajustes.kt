package com.thenikito.streamplay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.thenikito.streamplay.Atualizador
import com.thenikito.streamplay.Conta
import com.thenikito.streamplay.Dia
import com.thenikito.streamplay.Estado
import com.thenikito.streamplay.Fonte
import com.thenikito.streamplay.Store
import com.thenikito.streamplay.Vlc
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun TelaAjustes() {
    val m = medidas()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val fechar = remember { FocusRequester() }
    val controle = usandoControle()
    var confirmar by remember { mutableStateOf<String?>(null) }
    var autoNext by remember { mutableStateOf(Store.autoNext) }
    var cache by remember { mutableIntStateOf(Store.caching) }
    BackHandler { Estado.fecharAjustes() }
    LaunchedEffect(Unit) { if (controle) { delay(120); try { fechar.requestFocus() } catch (_: Exception) { } } }

    val src = Store.source ?: ""
    val arquivo = !Fonte.isUrl(src)
    val conta = Estado.contaAtual?.acesso

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.matchParentSize().bloqueiaToques { Estado.fecharAjustes() })
        Column(
            Modifier
                .padding(top = if (m.largo) WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 24.dp else 0.dp)
                .widthIn(max = 760.dp)
                .fillMaxWidth(if (m.largo) 0.92f else 1f)
                .clip(RoundedCornerShape(if (m.largo) 8.dp else 0.dp))
                .background(Color(0xFF181818))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(
                    top = if (m.largo) 20.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp,
                    bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Configurações", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                IconeBotao(Icons.Filled.Close, "Fechar", { Estado.fecharAjustes() }, focusRequester = fechar, fundo = Color(0xFF2A2A2A))
            }

            Secao("Conta")
            Text("Conectado como ${Store.login ?: ""}", color = Color.White, fontSize = 16.sp)
            val sub = buildList {
                if (!conta?.plano.isNullOrBlank()) add(conta!!.plano!!)
                conta?.faturaVenc?.let { add("próximo vencimento ${Dia.fmt(it)} (${Conta.dinheiro(conta.faturaValor)})") }
            }.joinToString("  •  ")
            Text(if (sub.isEmpty()) "Acesso liberado." else sub.replaceFirstChar { it.uppercase() }, color = Cor.Sub, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
            Botao("Sair da conta", { confirmar = "sair" }, Modifier.padding(top = 12.dp))

            Secao("Sua lista")
            Text(Fonte.nomeAmigavel(ctx, src), color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(Estado.lib?.summary ?: "", color = Cor.Sub, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            Text(
                if (arquivo) "● O app confere se o arquivo mudou sempre que você volta para ele."
                else "● Lista online: o app confere novidades sozinho a cada 10 minutos (e quando você volta para ele).",
                color = Cor.Verde, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Botao("Recarregar agora", { Estado.recarregar() })
                Botao("Trocar lista", { Estado.mostrarFonte(null) })
            }

            Secao("Reprodução")
            var f by remember { mutableStateOf(false) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .foco(RoundedCornerShape(6.dp), escala = 1f, aoFocar = { f = it }) {
                        autoNext = !autoNext
                        Store.autoNext = autoNext
                        Store.save()
                    }
                    .background(if (f) Color(0xFF2E2E2E) else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Tocar o próximo episódio automaticamente", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(
                    autoNext, null,
                    colors = SwitchDefaults.colors(checkedTrackColor = Cor.Vermelho, checkedThumbColor = Color.White),
                )
            }
            Text("Tempo de carregamento (aumente se o vídeo ficar travando)", color = Cor.Sub, fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (ms in listOf(1000, 1500, 3000, 5000, 8000)) {
                    val rotulo = (if (ms % 1000 == 0) "${ms / 1000}" else "${ms / 1000},${(ms % 1000) / 100}") + " s"
                    Chip(rotulo, cache == ms, {
                        cache = ms
                        Store.caching = ms
                        Store.save()
                    })
                }
            }
            Botao("Limpar \"Continuar assistindo\"", { confirmar = "progresso" }, Modifier.padding(top = 16.dp))

            Secao("Atualizações")
            Text(
                Estado.statusAtualizacao.ifEmpty { "Versão ${Atualizador.atual}. O app procura atualizações sozinho ao abrir e a cada 30 minutos." },
                color = Cor.Sub, fontSize = 14.sp,
            )
            val nova = Estado.novaVersao
            Botao(
                if (nova != null) "Atualizar para a versão ${nova.versao}" else "Procurar atualizações",
                {
                    if (nova != null) ctx.activity()?.let { Estado.instalarAtualizacao(it) }
                    else scope.launch { Estado.procurarAtualizacao(manual = true) }
                },
                Modifier.padding(top = 10.dp), habilitado = !Estado.baixando,
            )

            Secao("Como organizar sua lista")
            Text(GUIA, fontSize = 12.sp, color = Cor.Sub, fontFamily = FontFamily.Monospace, lineHeight = 18.sp)

            Spacer(Modifier.height(24.dp))
            val vlc = Vlc.versao
            Text(
                "StreamPLAY ${Atualizador.atual}" + (if (vlc != null) "  •  motor VLC $vlc" else ""),
                color = Cor.Dim, fontSize = 12.sp,
            )
        }
    }

    when (confirmar) {
        "sair" -> Confirmar(
            "Sair da sua conta? Para usar o StreamPLAY de novo, será preciso digitar o login e a senha.", "Sair",
            { confirmar = null; Estado.sairDaConta(null) }, { confirmar = null },
        )
        "progresso" -> Confirmar(
            "Apagar o progresso de tudo que você estava assistindo?", "Apagar",
            {
                confirmar = null
                Store.progress.clear()
                Store.save()
                Estado.toast("\"Continuar assistindo\" foi limpo")
            },
            { confirmar = null },
        )
    }
}

@Composable
private fun Secao(titulo: String) {
    Text(
        titulo, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Cor.Dim,
        modifier = Modifier.padding(top = 26.dp, bottom = 8.dp),
    )
}

@Composable
fun Confirmar(texto: String, sim: String, aoSim: () -> Unit, aoNao: () -> Unit) {
    val cancelar = remember { FocusRequester() }
    val controle = usandoControle()
    Dialog(onDismissRequest = aoNao) {
        Column(
            Modifier
                .widthIn(max = 440.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF202020))
                .border(1.dp, Cor.Linha, RoundedCornerShape(8.dp))
                .padding(22.dp),
        ) {
            Text(texto, color = Color.White, fontSize = 16.sp, lineHeight = 23.sp)
            Row(Modifier.padding(top = 20.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                Botao("Cancelar", aoNao, focusRequester = cancelar)
                Botao(sim, aoSim, primario = true)
            }
        }
    }
    LaunchedEffect(Unit) { if (controle) { delay(120); try { cancelar.requestFocus() } catch (_: Exception) { } } }
}
