package com.thenikito.streamplay

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
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
    private class Versao(var etag: String? = null, var lastModified: String? = null, var hash: String? = null)
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

    /** Lê a lista e o canais.m3u ao lado dela. [progresso] recebe textos como "Organizando sua lista... 120 mil itens". */
    suspend fun carregar(p: Pastas, fonte: String, progresso: (String) -> Unit): Library = withContext(Dispatchers.IO) {
        if (isUrl(fonte)) progresso("Baixando sua lista...")
        val a = ler(p, fonte, condicional = false, SALVA, progresso)!!
        val comp = companheira(fonte)
        val b = if (comp == null) null else try {
            ler(p, comp, condicional = false, SALVA_CANAIS, progresso)
        } catch (_: IOException) {
            null   // sem canais.m3u (ou fora do ar): só a lista
        }
        if (b == null) File(p.dados, SALVA_CANAIS).delete()
        this@Fonte.fonte = fonte
        principal = a
        canais = b
        juntar(a, b)
    }

    /** Confere a lista e o canais.m3u; baixa só o que mudou. Devolve null se nada mudou. */
    suspend fun carregarSeMudou(p: Pastas, fonte: String): Library? = withContext(Dispatchers.IO) {
        val a0 = principal
        if (fonte != this@Fonte.fonte || a0 == null) return@withContext carregar(p, fonte) {}
        var mudou = false
        ler(p, fonte, condicional = true, SALVA) {}?.let { principal = it; mudou = true }
        val comp = companheira(fonte)
        if (comp != null) {
            try {
                ler(p, comp, condicional = true, SALVA_CANAIS) {}?.let { canais = it; mudou = true }
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
    suspend fun carregarSalva(p: Pastas, fonte: String, progresso: (String) -> Unit): Library? = withContext(Dispatchers.IO) {
        val f = File(p.dados, SALVA)
        if (!f.exists()) return@withContext null
        val a = ler(f, if (isUrl(fonte)) fonte else null, progresso)
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
        if (b == null || (b.allChannels.isEmpty() && b.allMovies.isEmpty() && b.allSeries.isEmpty())) return a
        val r = Library()
        for (lib in arrayOf(a, b)) {
            for (k in Kind.entries) {
                val mapa = r.mapas[k.ordinal]
                // (sem putIfAbsent: ele só existe a partir do Android 7)
                for (i in lib.all(k)) if (!mapa.containsKey(i.id)) { mapa[i.id] = i; r.all(k).add(i) }
            }
            for ((id, e) in lib.episodios) if (!r.episodios.containsKey(id)) r.episodios[id] = e
        }
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

    // Lê um arquivo (link ou content://). Com condicional, devolve null se não mudou desde a última vez.
    private fun ler(p: Pastas, origem: String, condicional: Boolean, salvarComo: String, progresso: (String) -> Unit): Library? {
        val v = synchronized(versoes) { versoes.getOrPut(origem) { Versao() } }
        val tmp = File(p.cache, "$salvarComo.tmp")
        val hash: String
        if (isUrl(origem)) {
            val c = abrir(origem, if (condicional) v else null) ?: return null
            try {
                hash = c.inputStream.use { copiar(it, tmp) }
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
            return null
        }
        val lib = ler(tmp, if (isUrl(origem)) origem else null, progresso)
        v.hash = hash
        val salva = File(p.dados, salvarComo)
        if (!tmp.renameTo(salva)) { tmp.copyTo(salva, true); tmp.delete() }
        return lib
    }

    private fun ler(f: File, base: String?, progresso: (String) -> Unit): Library {
        progresso("Organizando sua lista...")
        val cs = if (utf8Valido(f)) Charsets.UTF_8 else Charsets.ISO_8859_1
        return InputStreamReader(FileInputStream(f), cs).buffered(1 shl 16).use { r ->
            M3U.parse(r, base) { n -> progresso("Organizando sua lista...  ${milhares(n)} itens") }
        }
    }

    private fun milhares(n: Int) = if (n >= 1000) "${n / 1000} mil" else n.toString()

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

    private fun copiar(ins: InputStream, destino: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        destino.outputStream().use { out ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                out.write(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Abre a conexão seguindo redirecionamentos (inclusive http -> https). Com [v], devolve null se não mudou (304). */
    private fun abrir(endereco: String, v: Versao?): HttpURLConnection? {
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
