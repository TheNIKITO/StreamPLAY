package com.thenikito.streamplay

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/** Lista online + canais.m3u no mesmo link, contra um servidor HTTP de verdade (com ETag, 304 e 404). */
class FonteTest {
    private lateinit var srv: ServerSocket
    private val arquivos = ConcurrentHashMap<String, String>()
    private val baixados = ConcurrentHashMap<String, Int>()
    private lateinit var base: String

    // servidor HTTP mínimo (o android.jar dos testes não tem o com.sun.net.httpserver)
    @Before
    fun ligar() {
        srv = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (true) {
                val s = try { srv.accept() } catch (_: SocketException) { break }
                s.use { atender(it) }
            }
        }.apply { isDaemon = true }.start()
        base = "http://127.0.0.1:${srv.localPort}"
    }

    @After
    fun desligar() = srv.close()

    private fun atender(s: java.net.Socket) {
        val r = s.getInputStream().bufferedReader()
        val caminho = (r.readLine() ?: return).split(' ')[1].substringBefore('?')
        var etagPedido: String? = null
        while (true) {
            val h = r.readLine() ?: break
            if (h.isEmpty()) break
            if (h.startsWith("If-None-Match:", true)) etagPedido = h.substringAfter(':').trim()
        }
        val out = s.getOutputStream()
        fun resposta(status: String, extra: String = "", b: ByteArray = ByteArray(0)) {
            val cab = "HTTP/1.1 $status\r\n${extra}Content-Length: ${b.size}\r\nConnection: close\r\n\r\n"
            out.write(cab.toByteArray())
            out.write(b)
            out.flush()
        }
        val corpo = arquivos[caminho]
        if (corpo == null) {
            resposta("404 Not Found")
            return
        }
        val etag = "\"" + corpo.hashCode() + "\""
        if (etagPedido == etag) {
            resposta("304 Not Modified")
            return
        }
        resposta("200 OK", "ETag: $etag\r\n", corpo.toByteArray())
        baixados.merge(caminho, 1, Int::plus)
    }

    private fun pastas(): Pastas {
        val d = Files.createTempDirectory("streamplay").toFile()
        return Pastas(File(d, "cache").apply { mkdirs() }, File(d, "dados").apply { mkdirs() }) { null }
    }

    private val lista = """
        |#EXTM3U
        |#EXTINF:-1 group-title="FILMES",Sintel
        |http://v.com/sintel.mp4
        |#EXTINF:-1 group-title="CANAIS | Esportes",Canal A
        |http://tv.com/a.m3u8
    """.trimMargin()

    private val canais = """
        |#EXTM3U
        |#EXTINF:-1 group-title="CANAIS = Esportes",Canal B
        |http://tv.com/b.m3u8
        |#EXTINF:-1 group-title="CANAIS =",Rádio C
        |http://tv.com/c.m3u8
    """.trimMargin()

    @Test
    fun canaisAoLadoDaListaEntramJuntoESoBaixaOQueMudou() = runBlocking {
        val p = pastas()
        val fonte = "$base/listas/lista.m3u"
        arquivos["/listas/lista.m3u"] = lista
        arquivos["/listas/canais.m3u"] = canais

        val lib = Fonte.carregar(p, fonte) {}
        assertEquals(listOf("Canal A", "Canal B", "Rádio C"), lib.allChannels.map { it.name })
        assertEquals(listOf("Esportes", "Canais"), lib.channels.map { it.name })
        assertEquals(listOf("Canal A", "Canal B"), lib.channels[0].items.map { it.name })
        assertEquals(1, lib.allMovies.size)

        // nada mudou: nenhum dos dois é baixado de novo (o servidor responde 304)
        assertNull(Fonte.carregarSeMudou(p, fonte))
        assertEquals(1, baixados["/listas/lista.m3u"])
        assertEquals(1, baixados["/listas/canais.m3u"])

        // só os canais mudaram: baixa só o canais.m3u
        arquivos["/listas/canais.m3u"] = canais + "\n#EXTINF:-1 group-title=\"CANAIS = Notícias\",Canal D\nhttp://tv.com/d.m3u8\n"
        val nova = Fonte.carregarSeMudou(p, fonte)
        assertNotNull(nova)
        assertEquals(4, nova!!.allChannels.size)
        assertEquals(listOf("Esportes", "Canais", "Notícias"), nova.channels.map { it.name })
        assertEquals(1, baixados["/listas/lista.m3u"])
        assertEquals(2, baixados["/listas/canais.m3u"])

        // a cópia salva (para abrir sem internet) também traz os canais
        assertEquals(4, Fonte.carregarSalva(p, fonte) {}!!.allChannels.size)

        // o canais.m3u foi apagado: os canais dele saem do app
        arquivos.remove("/listas/canais.m3u")
        val sem = Fonte.carregarSeMudou(p, fonte)
        assertEquals(listOf("Canal A"), sem!!.allChannels.map { it.name })
        assertNull(Fonte.carregarSeMudou(p, fonte))
    }

    @Test
    fun listaSemCanaisAoLado() = runBlocking {
        val p = pastas()
        arquivos["/lista.m3u"] = lista
        val lib = Fonte.carregar(p, "$base/lista.m3u") {}
        assertEquals(listOf("Canal A"), lib.allChannels.map { it.name })
        // o canais.m3u aparece depois: entra na próxima conferência
        arquivos["/canais.m3u"] = canais
        assertEquals(3, Fonte.carregarSeMudou(p, "$base/lista.m3u")!!.allChannels.size)
    }

    @Test
    fun ondeProcurarOCanaisM3u() {
        assertEquals("http://x.com/a/canais.m3u", Fonte.companheira("http://x.com/a/lista.m3u"))
        assertEquals("https://x.web.app/canais.m3u", Fonte.companheira("https://x.web.app/lista.m3u8?v=2"))
        assertNull(Fonte.companheira("http://painel.com/get.php?username=a&password=b&type=m3u"))
        assertNull(Fonte.companheira("http://x.com/canais.m3u"))
        assertNull(Fonte.companheira("content://com.android.providers/document/123"))
    }
}
