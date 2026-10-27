package com.thenikito.streamplay

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Limite de aparelhos. A leitura roda sempre; o fluxo completo roda contra o emulador do Firestore
 * (firebase emulators:start --only firestore, porta 8085, com as regras do PLAYGER) e é pulado sem ele.
 */
class AparelhosTest {
    @Test
    fun leAparelhosEHoras() {
        assertEquals(1761590717681L, Conta.instante("2025-10-27T18:45:17.681Z"))
        assertEquals(1761590717000L, Conta.instante("2025-10-27T18:45:17Z"))
        assertEquals(1761590717123L, Conta.instante("2025-10-27T18:45:17.123456Z"))
        val l = Conta.lerAparelhos(
            """{"documents":[{"name":"projects/p/databases/(default)/documents/acessos/x/aparelhos/abc",
               "fields":{"nome":{"stringValue":"TV – Sala"},"tipo":{"stringValue":"tv"},"visto":{"timestampValue":"2025-10-27T18:45:17Z"}}}]}""",
        )
        assertEquals("abc", l[0].id)
        assertEquals("TV – Sala", l[0].nome)
        assertEquals(0, Conta.lerAparelhos("{}").size)
    }

    @Test
    fun nomeDoAparelho() {
        Conta.definirAparelho(true, "Xiaomi", "MiBOX4")
        assertEquals("TV – Xiaomi MiBOX4", Conta.nomeAparelho)
        Conta.definirAparelho(false, "samsung", "SM-A546E")
        assertEquals("Celular – Samsung SM-A546E", Conta.nomeAparelho)
        Conta.definirAparelho(false, "Google", "Google Pixel 7")
        assertEquals("Celular – Google Pixel 7", Conta.nomeAparelho)
        assertEquals("celular", Conta.tipoAparelho)
    }

    private val raiz = "http://127.0.0.1:8085/v1/projects/playger-svect/databases/(default)/documents"

    // o emulador deixa o "owner" passar por cima das regras (é o painel gravando)
    private fun dono(metodo: String, caminho: String, campos: String? = null): Int {
        val gravar = campos != null
        val c = URL(if (gravar) "$raiz:commit" else "$raiz/$caminho").openConnection() as HttpURLConnection
        c.requestMethod = if (gravar) "POST" else metodo
        c.setRequestProperty("Authorization", "Bearer owner")
        c.setRequestProperty("Content-Type", "application/json")
        if (gravar) {
            val nome = "projects/playger-svect/databases/(default)/documents/$caminho"
            c.doOutput = true
            c.outputStream.use { it.write("""{"writes":[{"update":{"name":"$nome","fields":$campos}}]}""".toByteArray()) }
        }
        return c.responseCode.also { c.disconnect() }
    }

    private fun emulador() = try {
        (URL(raiz.substringBefore("/v1")).openConnection() as HttpURLConnection).run { connectTimeout = 500; responseCode; disconnect(); true }
    } catch (_: Exception) { false }

    @Test
    fun fluxoNoEmulador(): Unit = runBlocking {
        assumeTrue("emulador do Firestore desligado", emulador())
        Conta.base = "$raiz/acessos/"
        val id = Conta.idAcesso("android", "1234")
        val agora = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
        dono(
            "PATCH", "acessos/$id",
            """{"login":{"stringValue":"android"},"ativo":{"booleanValue":true},"manual":{"booleanValue":false},
               "negocio":{"stringValue":"PLAYGER"},"limite":{"integerValue":"1"}}""",
        )
        val tv = "c".repeat(32)
        dono("PATCH", "acessos/$id/aparelhos/$tv", """{"nome":{"stringValue":"TV – Sala"},"tipo":{"stringValue":"tv"},"visto":{"timestampValue":"$agora"}}""")
        Store.aparelho = "d".repeat(32)
        Conta.definirAparelho(false, "Samsung", "A54")

        val barrado = Conta.verificar(id)
        assertEquals(Conta.Estado.Bloqueado, barrado.estado)
        assertTrue(barrado.aparelhos)
        assertTrue(barrado.mensagem, barrado.mensagem.contains("1 aparelho (TV – Sala)") && barrado.mensagem.contains("PLAYGER"))

        dono("DELETE", "acessos/$id/aparelhos/$tv")
        assertEquals(Conta.Estado.Liberado, Conta.verificar(id).estado)
        val c = URL("$raiz/acessos/$id/aparelhos").openConnection() as HttpURLConnection
        val l = Conta.lerAparelhos(c.inputStream.bufferedReader().readText())
        assertEquals(listOf("d".repeat(32)), l.map { it.id })
        assertEquals("Celular – Samsung A54", l[0].nome)
        assertTrue(System.currentTimeMillis() - l[0].visto < 60_000)
        dono("DELETE", "acessos/$id/aparelhos/${"d".repeat(32)}")
        dono("DELETE", "acessos/$id")
    }
}
