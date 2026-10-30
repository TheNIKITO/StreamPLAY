package com.thenikito.streamplay

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

class ErroHttp(val codigo: Int) : IOException("HTTP $codigo")

/** Onde a lista é guardada e como abrir um arquivo escolhido no aparelho (nos testes, pastas comuns). */
class Pastas(val cache: File, val dados: File, val abrirArquivo: (String) -> InputStream?) {
    companion object {
        fun de(ctx: Context) = Pastas(ctx.cacheDir, ctx.filesDir) { ctx.contentResolver.openInputStream(Uri.parse(it)) }
    }
}

/**
 * De onde vem a lista: um link (http/https) ou um arquivo escolhido no aparelho (content://).
 * Cada arquivo é baixado e lido linha a linha; a última cópia fica salva para abrir sem internet.
 *
 * Canais à parte (como no PC): um canais.m3u ao lado da lista online (mesmo endereço) entra junto, sem precisar
 * de outro link. Cada arquivo é conferido separado, então quando só os canais mudam a lista grande não é baixada
 * de novo. (Um arquivo escolhido no aparelho não tem "vizinho": o Android só deixa ler o arquivo escolhido.)
 */
object Fonte {
    const val ARQUIVO_CANAIS = "canais.m3u"
    private const val SALVA = "lista.m3u"
    private const val SALVA_CANAIS = "canais.m3u"

    // ETag/Last-Modified e o hash do conteúdo de cada link, para só reorganizar quando mudar
    internal class Versao(var etag: String? = null, var lastModified: String? = null, var hash: String? = null)
    private val versoes = HashMap<String, Versao>()

    // última leitura de cada arquivo: quando só um muda, o outro não precisa ser baixado/lido de novo
    private var fonte: String? = null
    private var principal: Library? = null
    private var canais: Library? = null

    fun isUrl(s: String?) = s != null && M3U.isUrl(s)

    /** Onde procurar o canais.m3u para esta lista (ou null se não se aplica). */
    fun companheira(source: String?): String? {
        if (source.isNullOrBlank() || !isUrl(source)) return null
        val u = try { URL(source) } catch (_: Exception) { return null }
        val nome = u.path.substringAfterLast('/')
        // só para listas que são um arquivo .m3u publicado (não para links de painel com ?usuario=...)
        if (!Regex("\\.m3u8?$", RegexOption.IGNORE_CASE).containsMatchIn(nome) || nome.equals(ARQUIVO_CANAIS, true)) return null
        return URL(u, ARQUIVO_CANAIS).toString()
    }

    /**
     * Andamento do carregamento (0 a 1) repartido em etapas: baixar, organizar, canais. Só avisa a tela quando o
     * número da % muda, e nunca volta para trás.
     */
    class Andamento(private val tela: (Float) -> Unit) {
        private var pc = -1
        fun marcar(v: Double) {
            val p = (v.coerceIn(0.0, 1.0) * 100).toInt()
            if (p <= pc) return
            pc = p
            tela(p / 100f)
        }
        fun trecho(de: Double, ate: Double): (Double) -> Unit = { marcar(de + (ate - de) * it.coerceIn(0.0, 1.0)) }
    }

    /** Lê a lista e o canais.m3u ao lado dela. [progresso] recebe o andamento de 0 a 1 (para a barra com a %). */
    suspend fun carregar(p: Pastas, fonte: String, progresso: (Float) -> Unit): Library = withContext(Dispatchers.IO) {
        val an = Andamento(progresso)
        val a = lerOuSalva(p, fonte, SALVA, an.trecho(0.0, 0.55), an.trecho(0.55, 0.88))
        val comp = companheira(fonte)
        val b = if (comp == null) null else try {
            lerOuSalva(p, comp, SALVA_CANAIS, organizando = an.trecho(0.88, 0.97))
        } catch (_: IOException) {
            null   // sem canais.m3u (ou fora do ar): só a lista
        }
        if (b == null) File(p.dados, SALVA_CANAIS).delete()
        this@Fonte.fonte = fonte
        principal = a
        canais = b
        an.marcar(0.97)
        juntar(a, b).also { an.marcar(1.0) }
    }

    /** Confere a lista e o canais.m3u; baixa só o que mudou. Devolve null se nada mudou. */
    suspend fun carregarSeMudou(p: Pastas, fonte: String): Library? = withContext(Dispatchers.IO) {
        val a0 = principal
        if (fonte != this@Fonte.fonte || a0 == null) return@withContext carregar(p, fonte) {}
        var mudou = false
        ler(p, fonte, condicional = true, SALVA)?.let { principal = it; mudou = true }
        val comp = companheira(fonte)
        if (comp != null) {
            try {
                ler(p, comp, condicional = true, SALVA_CANAIS)?.let { canais = it; mudou = true }
            } catch (e: ErroHttp) {
                if (e.codigo == 404) {
                    // o canais.m3u foi apagado: os canais saem do app
                    if (canais != null) { canais = null; mudou = true }
                    synchronized(versoes) { versoes.remove(comp) }
                    File(p.dados, SALVA_CANAIS).delete()
                }
            } catch (_: IOException) {
                // sem internet: fica como está
            }
        }
        if (mudou) juntar(principal!!, canais) else null
    }

    /** A última cópia baixada desta lista (e dos canais), para abrir sem internet (ou null). */
    suspend fun carregarSalva(p: Pastas, fonte: String): Library? = withContext(Dispatchers.IO) {
        val f = File(p.dados, SALVA)
        if (!f.exists()) return@withContext null
        val a = ler(f, if (isUrl(fonte)) fonte else null) {}
        val fc = File(p.dados, SALVA_CANAIS)
        val comp = companheira(fonte)
        val b = if (comp != null && fc.exists()) ler(fc, comp) {} else null
        juntar(a, b)
    }

    /**
     * Junta a lista principal com a de canais (b pode ser null). Não mexe nas duas — cria categorias novas —
     * para poder juntar de novo quando só uma delas mudar. Igual ao M3U.Juntar do PC.
     */
    fun juntar(a: Library, b: Library?): Library {
        if (b == null || (b.allChannels.isEmpty() && b.allMovies.isEmpty() && b.allSeries.isEmpty() && b.guias.isEmpty())) return a
        val r = Library()
        for (lib in arrayOf(a, b)) {
            for (k in Kind.entries) {
                val mapa = r.mapas[k.ordinal]
                // (sem putIfAbsent: ele só existe a partir do Android 7)
                for (i in lib.all(k)) if (!mapa.containsKey(i.id)) { mapa[i.id] = i; r.all(k).add(i) }
            }
            for ((id, e) in lib.episodios) if (!r.episodios.containsKey(id)) r.episodios[id] = e
        }
        for (g in b.guias + a.guias) if (g !in r.guias) r.guias.add(g)
        for (k in Kind.entries) {
            val mapa = r.mapas[k.ordinal]
            val porNome = HashMap<String, Category>()
            val jaTem = HashMap<String, HashSet<Item>>()
            for (c in a.cats(k) + b.cats(k)) {
                val itens = c.items.map { mapa[it.id] ?: it }
                val n = M3U.norm(c.name)
                val dest = porNome[n]
                if (dest == null) {
                    val nova = Category(c.name, k, c.isRelease, porNome.size)
                    nova.items.addAll(itens)
                    porNome[n] = nova
                    r.cats(k).add(nova)
                    continue
                }
                // mesma categoria nas duas listas: soma os itens sem repetir
                val tem = jaTem.getOrPut(n) { HashSet(dest.items) }
                for (i in itens) if (tem.add(i)) dest.items.add(i)
            }
            val ordem = r.cats(k).sortedWith(compareBy<Category>({ !it.isRelease }, { it.order }))
            r.cats(k).clear()
            r.cats(k).addAll(ordem)
        }
        return r
    }

    /**
     * Ao abrir o app: com a cópia salva deste link, só pergunta ao servidor se mudou (ETag/Last-Modified). Sem mudança,
     * o servidor responde 304 (quase nada de download) e a lista sai da cópia; igual ao app do PC. Antes o celular/TV
     * baixava a lista inteira a cada abertura, o que gastava a cota do Firebase.
     */
    private fun lerOuSalva(
        p: Pastas, origem: String, salvarComo: String,
        baixando: (Double) -> Unit = {}, organizando: (Double) -> Unit = {},
    ): Library {
        val salva = File(p.dados, salvarComo)
        val v = versao(p, origem, salvarComo)
        if (isUrl(origem) && salva.exists() && (v.etag != null || v.lastModified != null || v.hash != null)) {
            ler(p, origem, condicional = true, salvarComo, baixando, organizando)?.let { return it }
            baixando(1.0)
            return ler(salva, origem, organizando)
        }
        return ler(p, origem, condicional = false, salvarComo, baixando, organizando)!!
    }

    // A versão de cada link (ETag, Last-Modified e o hash do conteúdo) fica num arquivo ao lado da cópia salva, para
    // valer também depois que o app fecha. Só vale para o mesmo link (trocou de lista: baixa tudo).
    private fun versao(p: Pastas, origem: String, salvarComo: String): Versao = synchronized(versoes) {
        versoes.getOrPut(origem) {
            try {
                val f = File(p.dados, "$salvarComo.versao")
                val l = if (f.exists() && File(p.dados, salvarComo).exists()) f.readLines() else emptyList()
                if (l.size >= 4 && l[0] == origem) Versao(l[1].ifEmpty { null }, l[2].ifEmpty { null }, l[3].ifEmpty { null }) else Versao()
            } catch (_: IOException) {
                Versao()
            }
        }
    }

    /** Só para os testes: esquece o que está na memória, como se o app tivesse fechado e aberto de novo. */
    internal fun esquecerNaMemoria() {
        synchronized(versoes) { versoes.clear() }
        fonte = null
        principal = null
        canais = null
    }

    private fun guardarVersao(p: Pastas, origem: String, salvarComo: String, v: Versao) {
        try {
            File(p.dados, "$salvarComo.versao").writeText(listOf(origem, v.etag ?: "", v.lastModified ?: "", v.hash ?: "").joinToString("\n"))
        } catch (_: IOException) {
        }
    }

    // Lê um arquivo (link ou content://). Com condicional, devolve null se não mudou desde a última vez.
    private fun ler(
        p: Pastas, origem: String, condicional: Boolean, salvarComo: String,
        baixando: (Double) -> Unit = {}, organizando: (Double) -> Unit = {},
    ): Library? {
        val v = versao(p, origem, salvarComo)
        val tmp = File(p.cache, "$salvarComo.tmp")
        val hash: String
        if (isUrl(origem)) {
            val c = abrir(origem, if (condicional) v else null) ?: return null
            try {
                // com compressão o servidor não diz o tamanho final: usa o da última cópia salva como estimativa
                val total = c.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0 } ?: File(p.dados, salvarComo).length()
                hash = c.inputStream.use { copiar(it, tmp, total, baixando) }
                v.etag = c.getHeaderField("ETag")
                v.lastModified = c.getHeaderField("Last-Modified")
            } finally {
                c.disconnect()
            }
        } else {
            val ins = p.abrirArquivo(origem) ?: throw FileNotFoundException(origem)
            hash = ins.use { copiar(it, tmp) }
        }
        if (condicional && hash == v.hash) {
            tmp.delete()
            if (isUrl(origem)) guardarVersao(p, origem, salvarComo, v)   // mesmo conteúdo: só renova o ETag salvo
            return null
        }
        baixando(1.0)
        val lib = ler(tmp, if (isUrl(origem)) origem else null, organizando)
        v.hash = hash
        val salva = File(p.dados, salvarComo)
        if (!tmp.renameTo(salva)) { tmp.copyTo(salva, true); tmp.delete() }
        if (isUrl(origem)) guardarVersao(p, origem, salvarComo, v)
        return lib
    }

    private fun ler(f: File, base: String?, organizando: (Double) -> Unit): Library {
        organizando(0.0)
        val cs = if (utf8Valido(f)) Charsets.UTF_8 else Charsets.ISO_8859_1
        val total = f.length().coerceAtLeast(1).toDouble()
        val contador = Contador(FileInputStream(f))
        return InputStreamReader(contador, cs).buffered(1 shl 16).use { r ->
            M3U.parse(r, base) { organizando(contador.lidos / total) }
        }
    }

    /** Conta os bytes já lidos do arquivo (o andamento de "organizando" é a parte do arquivo já lida). */
    private class Contador(ins: InputStream) : FilterInputStream(ins) {
        var lidos = 0L
        override fun read(): Int = super.read().also { if (it >= 0) lidos++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) lidos += it }
    }

    // listas antigas salvas em Latin-1 (Bloco de Notas) continuam com os acentos certos
    private fun utf8Valido(f: File): Boolean {
        val dec = Charset.forName("UTF-8").newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            InputStreamReader(FileInputStream(f), dec).use { r ->
                val buf = CharArray(1 shl 16)
                while (r.read(buf) >= 0) Unit
            }
            true
        } catch (_: IOException) {
            false
        }
    }

    // sem o tamanho total, o andamento segue uma curva que se aproxima de 100% sem chegar
    private fun copiar(ins: InputStream, destino: File, total: Long = 0, baixando: (Double) -> Unit = {}): String {
        val md = MessageDigest.getInstance("SHA-1")
        destino.outputStream().use { out ->
            val buf = ByteArray(1 shl 16)
            var lidos = 0L
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                out.write(buf, 0, n)
                lidos += n
                baixando(if (total > 0) minOf(0.99, lidos.toDouble() / total) else 1 - 1 / (1 + lidos / 8e6))
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Abre a conexão seguindo redirecionamentos (inclusive http -> https). Com [v], devolve null se não mudou (304). */
    internal fun abrir(endereco: String, v: Versao?): HttpURLConnection? {
        var url = URL(endereco)
        repeat(6) {
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 20000
            c.readTimeout = 60000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "StreamPLAY/1.0 (VLC)")
            if (v != null) {
                v.etag?.let { c.setRequestProperty("If-None-Match", it) }
                v.lastModified?.let { c.setRequestProperty("If-Modified-Since", it) }
            }
            val codigo = c.responseCode
            when {
                codigo == 304 -> { c.disconnect(); return null }
                codigo in 300..399 -> {
                    val destino = c.getHeaderField("Location")
                    c.disconnect()
                    url = URL(url, destino ?: throw IOException("redirecionamento sem destino"))
                }
                codigo !in 200..299 -> { c.disconnect(); throw ErroHttp(codigo) }
                else -> return c
            }
        }
        throw IOException("redirecionamentos demais")
    }

    fun mensagemErro(e: Throwable): String = when (e) {
        is ErroHttp -> "o servidor respondeu com erro ${e.codigo}"
        is UnknownHostException -> "sem internet ou endereço inválido"
        is SocketTimeoutException -> "o servidor demorou demais para responder"
        is FileNotFoundException -> "arquivo não encontrado"
        is SecurityException -> "sem permissão para ler o arquivo (escolha de novo)"
        is OutOfMemoryError -> "a lista é grande demais para a memória deste aparelho"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** Texto para mostrar nos Ajustes: o link, ou o nome do arquivo escolhido. */
    fun nomeAmigavel(ctx: Context, fonte: String?): String {
        if (fonte.isNullOrEmpty() || isUrl(fonte)) return fonte ?: ""
        return try {
            ctx.contentResolver.query(Uri.parse(fonte), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) "Arquivo do aparelho: " + it.getString(0) else null
            } ?: fonte
        } catch (_: Exception) {
            fonte
        }
    }
}
