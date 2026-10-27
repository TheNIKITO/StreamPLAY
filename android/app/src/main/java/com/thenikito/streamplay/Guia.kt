package com.thenikito.streamplay

import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import javax.xml.parsers.SAXParserFactory

class Programa(val inicio: Long, val fim: Long, val titulo: String, val descricao: String?) {
    val horario: String get() = "${Guia.hhmm(inicio)}–${Guia.hhmm(fim)}"
    fun progresso(agora: Long): Float =
        if (fim <= inicio) 0f else ((agora - inicio).toFloat() / (fim - inicio)).coerceIn(0f, 1f)
}

/**
 * Guia de programação (EPG) dos canais ao vivo — o mesmo do app do PC (Guia.cs). A lista diz de onde vem o guia
 * (#EXTM3U url-tvg="...") e cada canal diz o seu código nele (tvg-id). O arquivo XMLTV é baixado direto da fonte
 * (não passa pelo Firebase da lista), fica salvo no aparelho e é renovado a cada 6 horas; dele só se guarda a
 * programação dos canais da lista, das últimas horas até as próximas 36 horas.
 */
object Guia {
    private const val HORA = 60 * 60 * 1000L
    private const val VALIDADE = 6 * HORA

    @Volatile private var grade: Map<String, List<Programa>> = emptyMap()
    private var carga = 0

    /** Muda quando a programação é carregada ou atualizada (as telas leem para redesenhar). */
    val versao = mutableIntStateOf(0)

    val temAlgum: Boolean get() = grade.isNotEmpty()

    /** O programa que está passando agora e o próximo (qualquer um pode ser null). */
    fun agoraESeguir(canal: Item?, agora: Long): Pair<Programa?, Programa?> {
        val lista = canal?.tvgId?.let { grade[it.lowercase(Locale.ROOT)] } ?: return Pair(null, null)
        for (i in lista.indices) {
            val p = lista[i]
            if (p.fim <= agora) continue
            if (p.inicio <= agora) return Pair(p, lista.getOrNull(i + 1))
            return Pair(null, p)   // buraco na grade: nada agora, mas tem o próximo
        }
        return Pair(null, null)
    }

    /** Carrega o guia para os canais desta lista (chamar na thread da tela). Pode ser chamado de novo a qualquer hora. */
    suspend fun carregar(p: Pastas, lib: Library) {
        val minha = ++carga
        val nova = try {
            withContext(Dispatchers.IO) { montar(p, lib, System.currentTimeMillis()) }
        } catch (_: Throwable) {
            return   // sem guia desta vez: fica o que já tinha
        }
        if (minha != carga) return   // chegou uma lista mais nova enquanto lia
        grade = nova
        versao.intValue++
    }

    internal fun montar(p: Pastas, lib: Library, agora: Long): Map<String, List<Programa>> {
        val ids = HashSet<String>()
        for (c in lib.allChannels) c.tvgId?.let { ids.add(it.lowercase(Locale.ROOT)) }
        val g = HashMap<String, ArrayList<Programa>>()
        if (ids.isEmpty()) return g
        for (url in lib.guias) {
            val arquivo = obter(p, url) ?: continue
            try { ler(arquivo, ids, g, agora) } catch (_: Exception) { }
        }
        for (l in g.values) l.sortBy { it.inicio }
        return g
    }

    // Arquivo do guia salvo no aparelho: baixa de novo se passou da validade (ou se não tem). Sem internet, usa o salvo.
    private fun obter(p: Pastas, url: String): File? {
        val pasta = File(p.cache, "guia").apply { mkdirs() }
        val arquivo = File(pasta, hex(Hasher().hash(url)) + ".xml")
        if (arquivo.exists() && System.currentTimeMillis() - arquivo.lastModified() < VALIDADE) return arquivo
        return try {
            val c = Fonte.abrir(url, null)!!
            val tmp = File(pasta, arquivo.name + ".tmp")
            try {
                descompactado(c.inputStream).use { ins -> tmp.outputStream().use { ins.copyTo(it, 1 shl 16) } }
            } finally {
                c.disconnect()
            }
            if (!tmp.renameTo(arquivo)) { tmp.copyTo(arquivo, true); tmp.delete() }
            arquivo
        } catch (_: Exception) {
            if (arquivo.exists()) arquivo else null
        }
    }

    // guia publicado compactado (.xml.gz)
    private fun descompactado(ins: InputStream): InputStream {
        val b = BufferedInputStream(ins, 1 shl 16)
        b.mark(2)
        val gz = b.read() == 0x1F && b.read() == 0x8B
        b.reset()
        return if (gz) GZIPInputStream(b, 1 shl 16) else b
    }

    // Lê o XMLTV aos poucos (SAX), guardando só os canais pedidos e a janela de tempo útil.
    internal fun ler(arquivo: File, ids: Set<String>, grade: HashMap<String, ArrayList<Programa>>, agora: Long) {
        val de = agora - 3 * HORA
        val ate = agora + 36 * HORA
        val h = object : DefaultHandler() {
            var canal: String? = null
            var inicio = 0L
            var fim = 0L
            var titulo: String? = null
            var desc: String? = null
            var texto: StringBuilder? = null
            var campo = ""

            override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes) {
                val nome = qName?.takeIf { it.isNotEmpty() } ?: localName ?: return
                if (nome == "programme") {
                    canal = null
                    val c = a.getValue("channel")?.lowercase(Locale.ROOT) ?: return
                    if (c !in ids) return
                    val i = hora(a.getValue("start")) ?: return
                    val f = hora(a.getValue("stop")) ?: return
                    if (f < de || i > ate) return
                    canal = c; inicio = i; fim = f; titulo = null; desc = null
                } else if (canal != null && ((nome == "title" && titulo == null) || (nome == "desc" && desc == null))) {
                    campo = nome
                    texto = StringBuilder()
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                texto?.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val nome = qName?.takeIf { it.isNotEmpty() } ?: localName ?: return
                val t = texto
                if (t != null && nome == campo) {
                    val v = t.toString().trim()
                    if (campo == "title") titulo = v else desc = v
                    texto = null
                } else if (nome == "programme") {
                    val c = canal ?: return
                    canal = null
                    val tit = titulo?.takeIf { it.isNotEmpty() } ?: return
                    grade.getOrPut(c) { ArrayList() }.add(Programa(inicio, fim, tit, desc?.takeIf { it.isNotEmpty() }))
                }
            }

            // não busca o xmltv.dtd do <!DOCTYPE>
            override fun resolveEntity(publicId: String?, systemId: String?) = InputSource(StringReader(""))
        }
        val f = SAXParserFactory.newInstance()
        try { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) } catch (_: Exception) { }
        arquivo.inputStream().buffered(1 shl 16).use { f.newSAXParser().parse(InputSource(it), h) }
    }

    // XMLTV: "20261026190000 +0000" (o fuso é opcional, e sem ele vale UTC como no PC). Devolve em milissegundos.
    internal fun hora(s: String?): Long? {
        if (s == null || s.length < 14) return null
        for (i in 0 until 14) if (!s[i].isDigit()) return null
        fun n(a: Int, b: Int) = s.substring(a, b).toInt()
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(n(0, 4), n(4, 6) - 1, n(6, 8), n(8, 10), n(10, 12), n(12, 14))
        var t = c.timeInMillis
        val fuso = s.substring(14).trim()
        if (fuso.length == 5 && (fuso[0] == '+' || fuso[0] == '-') && fuso.substring(1).all { it.isDigit() }) {
            val min = fuso.substring(1, 3).toInt() * 60 + fuso.substring(3, 5).toInt()
            t -= (if (fuso[0] == '-') -min else min) * 60_000L
        }
        return t
    }

    private val fmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm", Locale.ROOT)
    }

    /** "19:30" no horário do aparelho. */
    fun hhmm(t: Long): String = fmt.get()!!.format(Date(t))

    /** Para os testes. */
    internal fun definir(g: Map<String, List<Programa>>) { grade = g }
}
