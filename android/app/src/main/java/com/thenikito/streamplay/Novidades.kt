package com.thenikito.streamplay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Novidades que o dono escreve no PLAYGER (aba Novidades), igual ao Novidades.cs do PC. O site mostra todas; o app
 * mostra cada novidade nova uma vez, numa janela ao abrir (já na tela da lista). Ficam em novidades/{id} no Firestore,
 * que qualquer um pode ler. Só as dos últimos 45 dias (quem instala agora não recebe as antigas), no máximo 3 por vez.
 */
object Novidades {
    class Novidade(val id: String, val titulo: String, val texto: String, val data: Int)

    private const val DIAS = 45
    private const val MAX = 3
    const val SITE = "https://streamplay-site.web.app/#novidades"

    suspend fun novas(): List<Novidade> = withContext(Dispatchers.IO) {
        val raiz = Conta.base.trimEnd('/').substringBeforeLast('/')   // .../documents
        val c = URL("$raiz/novidades?pageSize=100&key=${Conta.CHAVE_API}").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 15000
            if (c.responseCode !in 200..299) return@withContext emptyList()
            filtrar(ler(c.inputStream.bufferedReader().use { it.readText() }), Store.novidadesVistas, Dia.hoje())
        } finally {
            c.disconnect()
        }
    }

    /** As que ainda não apareceram neste aparelho e são dos últimos 45 dias, da mais nova para a mais antiga. */
    fun filtrar(todas: List<Novidade>, vistas: Collection<String>, hoje: Int): List<Novidade> =
        todas.filter { it.id !in vistas && Dia.dias(it.data, hoje) <= DIAS }
            .sortedWith(compareByDescending<Novidade> { it.data }.thenByDescending { it.id })
            .take(MAX)

    fun marcarVistas(l: List<Novidade>) {
        for (n in l) if (n.id !in Store.novidadesVistas) Store.novidadesVistas.add(n.id)
        while (Store.novidadesVistas.size > 300) Store.novidadesVistas.removeAt(0)
        Store.save()
    }

    fun ler(json: String): List<Novidade> {
        val docs = JSONObject(json).optJSONArray("documents") ?: return emptyList()
        val l = ArrayList<Novidade>()
        for (i in 0 until docs.length()) {
            val d = docs.getJSONObject(i)
            val f = d.optJSONObject("fields") ?: continue
            fun t(n: String) = f.optJSONObject(n)?.optString("stringValue")?.trim().orEmpty()
            val data = Dia.parse(t("data")) ?: continue
            if (t("titulo").isEmpty()) continue
            l.add(Novidade(d.getString("name").substringAfterLast('/'), t("titulo"), t("texto"), data))
        }
        return l
    }

    private val MESES = listOf("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro")

    /** 20261031 -> "31 de outubro de 2026" */
    fun dataPorExtenso(d: Int) = "${d % 100} de ${MESES[(d / 100) % 100 - 1]} de ${d / 10000}"
}
