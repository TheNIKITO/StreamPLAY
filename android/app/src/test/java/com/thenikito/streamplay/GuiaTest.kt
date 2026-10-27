package com.thenikito.streamplay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/** Guia de programação: cabeçalho url-tvg, tvg-id dos canais e leitura do XMLTV (compactado, com DOCTYPE). */
class GuiaTest {
    private lateinit var srv: ServerSocket
    private lateinit var pasta: File
    private val pedidos = AtomicInteger()
    private var xml = ""

    private val agora = Guia.hora("20261027180000 +0000")!!

    @Before
    fun ligar() {
        pasta = Files.createTempDirectory("guia").toFile()
        srv = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (true) {
                val s = try { srv.accept() } catch (_: SocketException) { break }
                s.use {
                    val ent = it.getInputStream().bufferedReader()
                    while (!ent.readLine().isNullOrEmpty()) { }
                    pedidos.incrementAndGet()
                    val gz = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { z -> z.write(xml.toByteArray()) } }.toByteArray()
                    it.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${gz.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(gz)
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    @After
    fun desligar() {
        srv.close()
        pasta.deleteRecursively()
    }

    private fun lista(guia: String) = """
        #EXTM3U url-tvg="$guia" x-tvg-url="$guia, http://outro/epg.xml"
        #EXTINF:-1 tvg-id="Globo.br" tvg-logo="g.png" group-title="CANAIS = Abertos",Globo HD
        http://teste/live/globo.m3u8
        #EXTINF:-1 tvg-id="sportv.br" group-title="CANAIS = Esportes",SporTV
        http://teste/live/sportv.m3u8
        #EXTINF:-1 group-title="CANAIS = Esportes",Sem Guia
        http://teste/live/semguia.m3u8
        #EXTINF:-1 tvg-id="nao-e-canal" group-title="FILMES | Ação",Filme
        http://teste/movie/1.mp4
    """.trimIndent()

    @Test
    fun cabecalhoETvgId() {
        val lib = M3U.parse(lista("http://epg/a.xml").reader().buffered(), null)
        assertEquals(listOf("http://epg/a.xml", "http://outro/epg.xml"), lib.guias)
        assertEquals("Globo.br", lib.allChannels.first { it.name == "Globo HD" }.tvgId)
        assertNull(lib.allChannels.first { it.name == "Sem Guia" }.tvgId)
        assertNull(lib.allMovies.first().tvgId)
        // canais.m3u com o guia + lista principal sem guia: o guia continua valendo
        val principal = M3U.parse("#EXTM3U\n#EXTINF:-1 group-title=\"FILMES\",X\nhttp://t/movie/x.mp4".reader().buffered(), null)
        assertEquals(lib.guias, Fonte.juntar(principal, lib).guias)
    }

    @Test
    fun horas() {
        assertEquals(Guia.hora("20261027150000 -0300"), Guia.hora("20261027180000 +0000"))
        assertEquals(Guia.hora("20261027180000"), Guia.hora("20261027180000 +0000"))
        assertNull(Guia.hora("2026-10-27"))
        assertNull(Guia.hora(null))
    }

    @Test
    fun baixaLeEGuarda() {
        xml = """<?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tv SYSTEM "xmltv.dtd">
            <tv>
              <channel id="Globo.br"><display-name>Globo</display-name></channel>
              <programme start="20261027160000 +0000" stop="20261027173000 +0000" channel="Globo.br"><title lang="pt">Antes</title></programme>
              <programme start="20261027173000 +0000" stop="20261027190000 +0000" channel="Globo.br">
                <title lang="pt">Jornal &amp; Notícias</title><title lang="en">News</title><desc lang="pt">As notícias do dia.</desc>
              </programme>
              <programme start="20261027190000 +0000" stop="20261027200000 +0000" channel="globo.br"><title>Novela</title></programme>
              <programme start="20261020190000 +0000" stop="20261020200000 +0000" channel="Globo.br"><title>Semana passada</title></programme>
              <programme start="20261027170000 +0000" stop="20261027190000 +0000" channel="Outro.br"><title>Fora da lista</title></programme>
              <programme start="20261027200000 +0000" stop="20261027210000 +0000" channel="sportv.br"><title>Futebol</title></programme>
            </tv>"""
        val lib = M3U.parse(lista("http://127.0.0.1:${srv.localPort}/epg.xml.gz").reader().buffered(), null)
        val p = Pastas(pasta, pasta) { null }
        val g = Guia.montar(p, lib, agora)
        assertEquals(setOf("globo.br", "sportv.br"), g.keys)
        assertEquals(listOf("Antes", "Jornal & Notícias", "Novela"), g["globo.br"]!!.map { it.titulo })
        Guia.definir(g)

        val globo = lib.allChannels.first { it.name == "Globo HD" }
        val (a, s) = Guia.agoraESeguir(globo, agora)
        assertEquals("Jornal & Notícias", a!!.titulo)
        assertEquals("As notícias do dia.", a.descricao)
        assertEquals(1f / 3f, a.progresso(agora), 0.001f)
        assertEquals("Novela", s!!.titulo)
        // buraco na grade: nada agora, mas mostra o próximo
        val (a2, s2) = Guia.agoraESeguir(lib.allChannels.first { it.name == "SporTV" }, agora)
        assertNull(a2)
        assertEquals("Futebol", s2!!.titulo)
        assertEquals(Pair(null, null), Guia.agoraESeguir(lib.allChannels.first { it.name == "Sem Guia" }, agora))

        // o segundo link (http://outro) não responde: não atrapalha. O arquivo ficou salvo e não baixa de novo.
        val antes = pedidos.get()
        assertTrue(File(pasta, "guia").listFiles()!!.any { it.name.endsWith(".xml") })
        Guia.montar(p, lib, agora)
        assertEquals(antes, pedidos.get())
    }
}
