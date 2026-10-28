package com.thenikito.streamplay

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sincronização do "continuar assistindo" e da Minha lista. A junção roda sempre; o fluxo com a nuvem roda contra o
 * emulador do Firestore (porta 8085, regras do PLAYGER) e é pulado sem ele.
 */
class SincroniaTest {
    private fun limpar() {
        Store.progress.clear(); Store.favorites.clear(); Store.favLog.clear(); Store.progressoLimpo = 0; Sincronia.sujo = false
    }

    @Test
    fun valeOMaisRecente() {
        limpar()
        Store.progress["mv:a"] = Progresso(1000, 9000, 200, null)
        Store.progress["mv:b"] = Progresso(1000, 9000, 900, null)
        Store.favorites.add("mv:x")
        val n = Sincronia.Nuvem(
            mapOf("mv:a" to Progresso(5000, 9000, 500, null), "mv:b" to Progresso(2000, 9000, 100, null), "mv:c" to Progresso(3000, 9000, 300, null)),
            mapOf("mv:y" to FavMarca(true, 50), "mv:x" to FavMarca(false, 10)),
            0,
        )
        assertTrue(Sincronia.juntar(n))
        assertEquals(5000L, Store.progress["mv:a"]!!.pos)   // nuvem mais nova
        assertEquals(1000L, Store.progress["mv:b"]!!.pos)   // aqui mais novo
        assertEquals(3000L, Store.progress["mv:c"]!!.pos)   // só existia lá
        assertTrue("mv:y" in Store.favorites)
        assertFalse("mv:x" in Store.favorites)               // tirado em outro aparelho
        assertTrue(Sincronia.sujo)                           // mv:b daqui é mais novo: precisa subir
    }

    @Test
    fun limparValeParaTodos() {
        limpar()
        Store.progress["mv:a"] = Progresso(1000, 9000, 200, null)
        Store.progress["mv:b"] = Progresso(1000, 9000, 900, null)
        val n = Sincronia.Nuvem(mapOf("mv:z" to Progresso(1, 9000, 400, null)), emptyMap(), 500)
        Sincronia.juntar(n)
        assertEquals(setOf("mv:b"), Store.progress.keys)     // limpo em 500: some o de 200 e não entra o de 400
    }

    private val raiz = "http://127.0.0.1:8085/v1/projects/playger-svect/databases/(default)/documents"

    private fun emulador() = try {
        (URL(raiz.substringBefore("/v1")).openConnection() as HttpURLConnection).run { connectTimeout = 500; responseCode; disconnect(); true }
    } catch (_: Exception) { false }

    /** Com o PC já tendo mandado o dele (acesso "c" x 64): lê, avança o progresso de um filme do PC e favorita. */
    @Test
    fun conversaComOPc(): Unit = runBlocking {
        assumeTrue("emulador do Firestore desligado", emulador())
        val id = "c".repeat(64)
        Conta.base = "$raiz/acessos/"
        limpar()
        Sincronia.baixar(id)
        val doPc = Store.progress.keys.firstOrNull { it.startsWith("mv:") }
        assumeTrue("o PC ainda não mandou nada (rode o teste do PC antes)", doPc != null)
        val p = Store.progress[doPc!!]!!
        Store.setProgress(doPc, 4_000_000, p.dur, p.show)
        if (doPc !in Store.favorites) Store.toggleFav(doPc)
        assertTrue(Sincronia.sujo)
        Sincronia.enviar(id)
        assertFalse(Sincronia.sujo)
        // relendo do zero, a nuvem tem o que o Android mandou
        limpar()
        Sincronia.baixar(id)
        assertEquals(4_000_000L, Store.progress[doPc]!!.pos)
        assertTrue(doPc in Store.favorites)
        println("ANDROID_MANDOU $doPc")
    }
}
