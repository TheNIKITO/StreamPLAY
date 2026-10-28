package com.thenikito.streamplay.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thenikito.streamplay.Conta
import com.thenikito.streamplay.Estado
import com.thenikito.streamplay.M3U
import com.thenikito.streamplay.Store
import com.thenikito.streamplay.Tela
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

const val GUIA =
    "FILMES  →  group-title=\"FILMES\"\n" +
        "LANÇAMENTOS  →  group-title=\"FILMES | Lançamentos\"\n" +
        "Outras fileiras  →  group-title=\"FILMES | Ação\"\n\n" +
        "SÉRIES  →  group-title=\"SERIES | Drama\"\n" +
        "   e o nome do episódio assim: \"Nome da Série S01E01\"\n\n" +
        "CANAIS  →  group-title=\"CANAIS = Esportes\"  (ou \"CANAIS | Esportes\")\n" +
        "   os canais também podem ficar num canais.m3u ao lado da lista (no mesmo link)\n" +
        "   (qualquer outro grupo também vira canal)\n\n" +
        "Exemplo:\n" +
        "#EXTINF:-1 tvg-name=\"Nome do filme\" tvg-logo=\"http://capa.jpg\" group-title=\"FILMES | Lançamentos\"\n" +
        "http://link-do-video.mp4\n\n" +
        "Opcional na linha #EXTINF: descricao=\"...\" ano=\"2024\" genero=\"Ação\" nota=\"8.5\" fundo=\"http://imagem-larga.jpg\""

@Composable
private fun Cartaz(largura: Int = 440, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = largura.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) { content() }
    }
}

@Composable
fun Campo(
    valor: String,
    aoMudar: (String) -> Unit,
    rotulo: String,
    modifier: Modifier = Modifier,
    senha: Boolean = false,
    teclado: KeyboardType = KeyboardType.Text,
    acao: ImeAction = ImeAction.Next,
    aoConfirmar: () -> Unit = {},
) {
    OutlinedTextField(
        value = valor,
        onValueChange = aoMudar,
        label = { Text(rotulo) },
        singleLine = true,
        visualTransformation = if (senha) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (senha) KeyboardType.Password else teclado, imeAction = acao),
        keyboardActions = KeyboardActions(onAny = { aoConfirmar() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.White, unfocusedBorderColor = Color(0xFF6A6A6A),
            focusedLabelColor = Color.White, unfocusedLabelColor = Cor.Dim, cursorColor = Color.White,
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedContainerColor = Color(0xFF222222), unfocusedContainerColor = Color(0xFF1C1C1C),
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Tela de entrada com a conta do PLAYGER. */
@Composable
fun TelaLogin(aviso: String?) {
    var login by rememberSaveable { mutableStateOf(Store.login ?: "") }
    var senha by rememberSaveable { mutableStateOf("") }
    var erro by remember(aviso) { mutableStateOf(aviso) }
    var ocupado by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val campoLogin = remember { FocusRequester() }
    val campoSenha = remember { FocusRequester() }
    val controle = usandoControle()

    fun entrar() {
        if (ocupado) return
        val l = login.trim()
        if (l.isEmpty()) { erro = "Digite o seu login."; return }
        if (senha.isEmpty()) { erro = "Digite a sua senha."; return }
        ocupado = true
        erro = null
        scope.launch {
            val id = Conta.idAcesso(l, senha)
            val r = Conta.verificar(id)
            ocupado = false
            when (r.estado) {
                Conta.Estado.Invalido -> { erro = "Login ou senha incorretos."; senha = "" }
                Conta.Estado.SemConexao -> erro = r.mensagem
                else -> {
                    // login e senha certos: guarda para entrar sozinho da próxima vez (mesmo bloqueado, para "verificar de novo")
                    Store.login = l.lowercase()
                    Store.acessoId = id
                    Store.save()
                    if (r.estado == Conta.Estado.Bloqueado) Estado.contaBloqueada(r) else Estado.contaLiberada(r)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (controle) {
            delay(200)
            try { (if (login.isEmpty()) campoLogin else campoSenha).requestFocus() } catch (_: Exception) { }
        }
    }

    Cartaz {
        Logo(altura = 32.dp)
        Text("Entrar", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(top = 28.dp))
        Text("Use o login e a senha que você recebeu.", fontSize = 15.sp, color = Cor.Sub, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp))
        Campo(login, { login = it }, "Login", Modifier.focusRequester(campoLogin), aoConfirmar = {
            if (senha.isEmpty()) try { campoSenha.requestFocus() } catch (_: Exception) { } else entrar()
        })
        Spacer(Modifier.height(12.dp))
        Campo(senha, { senha = it }, "Senha", Modifier.focusRequester(campoSenha), senha = true, acao = ImeAction.Done, aoConfirmar = { entrar() })
        erro?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)) }
        Botao(if (ocupado) "Verificando..." else "Entrar", { entrar() }, Modifier.fillMaxWidth().padding(top = 22.dp), primario = true, habilitado = !ocupado)
    }
}

/** Login certo, mas o acesso está bloqueado: mostra o motivo, a chave Pix e "já paguei". */
@Composable
fun TelaBloqueio(r: Conta.Resultado) {
    var ocupado by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    val pix = r.acesso?.pix
    val porPagamento = r.acesso?.ativo == true && !r.acesso.manual && !r.aparelhos
    val deNovo = remember { FocusRequester() }
    val controle = usandoControle()
    LaunchedEffect(Unit) { if (controle) { delay(200); try { deNovo.requestFocus() } catch (_: Exception) { } } }

    fun verificar() {
        val id = Store.acessoId
        if (ocupado || id.isNullOrEmpty()) return
        ocupado = true
        status = null
        scope.launch {
            val r2 = Conta.verificar(id)
            ocupado = false
            when (r2.estado) {
                Conta.Estado.Liberado -> Estado.contaLiberada(r2)
                Conta.Estado.Bloqueado -> {
                    Estado.contaBloqueada(r2)
                    status = "Ainda não foi liberado. Assim que o pagamento for confirmado, tente de novo."
                }
                Conta.Estado.Invalido -> Estado.sairDaConta("Sua senha mudou ou o acesso foi removido. Entre de novo.")
                else -> status = r2.mensagem
            }
        }
    }

    Cartaz(480) {
        Logo(altura = 32.dp)
        Text(r.titulo, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(top = 28.dp))
        Text(r.mensagem, fontSize = 16.sp, color = Cor.Sub, lineHeight = 23.sp, modifier = Modifier.padding(top = 10.dp))
        if (porPagamento && !pix.isNullOrBlank()) {
            Column(
                Modifier
                    .padding(top = 20.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1F1F1F))
                    .border(1.dp, Cor.Linha, RoundedCornerShape(6.dp))
                    .padding(16.dp),
            ) {
                Text("Chave Pix", color = Cor.Dim, fontSize = 13.sp)
                Text(pix, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                Botao("Copiar chave Pix", {
                    clip.setText(AnnotatedString(pix))
                    Estado.toast("Chave Pix copiada")
                })
            }
        }
        Botao(
            if (ocupado) "Verificando..." else if (porPagamento) "Já paguei, verificar de novo" else "Verificar de novo",
            { verificar() }, Modifier.fillMaxWidth().padding(top = 22.dp), primario = true, habilitado = !ocupado, focusRequester = deNovo,
        )
        status?.let { Text(it, color = Cor.Sub, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)) }
        Botao("Entrar com outra conta", { Estado.sairDaConta(null) }, Modifier.fillMaxWidth().padding(top = 12.dp))
    }
}

/** Escolher a lista: link (http/https) ou arquivo do aparelho. */
@Composable
fun TelaFonte(t: Tela.Fonte) {
    val erro0 = t.erro
    val ctx = LocalContext.current
    var url by rememberSaveable { mutableStateOf("") }
    var erro by remember(t) { mutableStateOf(erro0) }
    var carregando by remember(t) { mutableStateOf(false) }
    val podeVoltar = Estado.lib != null && erro0 == null
    val campo = remember { FocusRequester() }
    val controle = usandoControle()

    val escolher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // guarda a permissão para ler o arquivo de novo depois que o app reabrir
            try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            erro = null
            carregando = true
            Estado.usarFonte(uri.toString())
        }
    }

    fun usarLink() {
        val u = url.trim()
        if (!M3U.isUrl(u)) { erro = "Cole um link que comece com http:// ou https://"; return }
        erro = null
        carregando = true
        Estado.usarFonte(u)
    }

    LaunchedEffect(Unit) { if (controle) { delay(200); try { campo.requestFocus() } catch (_: Exception) { } } }

    Cartaz(640) {
        if (podeVoltar) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            IconeBotao(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", { Estado.sairDaFonte() })
            Text("Voltar", color = Cor.Sub, fontSize = 15.sp, modifier = Modifier.padding(start = 6.dp))
        }
        Logo(altura = 32.dp)
        Text("Escolha sua lista", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(top = 24.dp))
        Text(
            "Cole o link da sua lista M3U (ou escolha um arquivo do aparelho). O StreamPLAY separa sozinho Canais, Filmes e Séries.",
            fontSize = 15.sp, color = Cor.Sub, lineHeight = 22.sp, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
        )
        Campo(url, { url = it }, "Link da lista (http:// ou https://)", Modifier.focusRequester(campo), teclado = KeyboardType.Uri, acao = ImeAction.Go, aoConfirmar = { usarLink() })
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Botao("Usar este link", { usarLink() }, primario = true, habilitado = !carregando)
            Botao("Escolher arquivo", {
                try { escolher.launch(arrayOf("*/*")) } catch (_: ActivityNotFoundException) {
                    erro = "Este aparelho não tem um seletor de arquivos. Use um link."
                }
            }, habilitado = !carregando)
        }
        if (carregando && erro == null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
            val p = Estado.andamento.coerceAtLeast(0f)
            BarraAndamento(p, Modifier.width(240.dp))
            Text("${(p * 100).roundToInt()}%", color = Cor.Sub, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
        }
        erro?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp)) }
        Text("Como organizar sua lista", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(top = 30.dp, bottom = 8.dp))
        Text(GUIA, fontSize = 12.sp, color = Cor.Sub, fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
    }
}
