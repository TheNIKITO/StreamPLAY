package com.thenikito.streamplay

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Busca com IA (botão ✨, só no celular). O servidor da lista no Cloudflare pergunta à IA o que procurar (palavras-chave
 * do TMDB, termos em português, gêneros e títulos) e o app cruza isso com a lista: só aparece o que existe nela. As
 * palavras-chave de cada título vêm do campo palavras= que o buscar-palavras-chave.bat coloca na lista. Igual ao Ia.cs.
 */
object Ia {
    /** Só muda nos testes (versão de depuração aberta com o extra "ia_url": servidor local com uma IA falsa). */
    var url = "https://streamplay-lista.streamplay-pix.workers.dev/ia"
    private const val MAX_RESULTADOS = 120

    class Resposta(
        val frase: String, val tipo: String, val palavrasChave: List<String>, val termos: List<String>,
        val generos: List<String>, val titulos: List<String>,
    )

    /** Erro que pode ser mostrado como está (vem do servidor ou é falta de conexão). */
    class Erro(msg: String) : Exception(msg)

    /** Pergunta à IA (rede: chamar fora da thread principal). */
    fun perguntar(pergunta: String): Resposta {
        val c: HttpURLConnection
        val codigo: Int
        val txt: String
        try {
            c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use {
                it.write(JSONObject().put("pergunta", pergunta).put("aparelho", Store.meuAparelho()).toString().toByteArray())
            }
            codigo = c.responseCode
            txt = (if (codigo in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (_: Exception) {
            throw Erro("Sem conexão com a IA. Confira a internet e tente de novo.")
        }
        val j = try { JSONObject(txt) } catch (_: Exception) { null }
        if (codigo !in 200..299 || j == null) throw Erro(j?.optString("erro")?.takeIf { it.isNotBlank() } ?: "A IA não respondeu. Tente de novo.")
        fun lista(nome: String): List<String> {
            val a: JSONArray = j.optJSONArray(nome) ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
        }
        return Resposta(j.optString("frase"), j.optString("tipo"), lista("palavras_chave"), lista("termos"), lista("generos"), lista("titulos"))
    }

    // ---------- cruzar a resposta com a lista ----------

    private val COLCHETES = Regex("\\[[^\\]]*\\]|\\([^)]*\\)")
    private val NAO_LETRA = Regex("[^A-Z0-9]+")
    private val ETIQUETAS = setOf("4K", "UHD", "HDR", "FHD", "HD", "SD", "DUB", "LEG", "DUBLADO", "LEGENDADO", "NACIONAL", "LANCAMENTO")

    /** Maiúsculas, sem acento nem pontuação, com espaço nas pontas (para achar palavras inteiras). */
    private fun limpo(s: String?): String = " " + NAO_LETRA.replace(M3U.norm(s), " ").trim() + " "

    /** O nome sem ano, [L], 4K etc.: "Moana 2 4K [L]" e "Moana 2" viram o mesmo título. */
    private fun nomeBase(nome: String?): String =
        " " + limpo(COLCHETES.replace(nome ?: "", " ")).split(' ').filter { it.isNotEmpty() && it !in ETIQUETAS }.joinToString(" ") + " "

    fun achar(r: Resposta, todos: List<Item>): List<Item> {
        val pool = when (r.tipo) {
            "filme" -> todos.filter { it.kind == Kind.Movie }
            "serie" -> todos.filter { it.kind == Kind.Series }
            else -> todos
        }
        val titulos = r.titulos.map { nomeBase(it) }.filter { it.isNotBlank() }
        val palavras = r.palavrasChave.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toHashSet()
        val parciais = palavras.filter { it.length >= 4 }.map { " $it " }
        val termos = r.termos.map { limpo(it) }.filter { it.trim().length >= 3 }.distinct()
        val generos = r.generos.map { M3U.norm(it) }.filter { it.isNotEmpty() }

        class Achado(val item: Item, val pontos: Int, val nome: String)
        val achados = ArrayList<Achado>()
        for (item in pool) {
            val nome = nomeBase(item.name)
            var pontos = 0
            // 1) um dos títulos que a IA citou (o primeiro vale mais; nome igual vale mais que "começa com")
            titulos.forEachIndexed { j, t ->
                val p = when {
                    nome == t -> 100 - j
                    nome.startsWith(t) -> 70 - j
                    t.length >= 8 && nome.contains(t) -> 40 - j
                    else -> 0
                }
                if (p > pontos) pontos = p
            }
            // 2) palavras-chave do TMDB: igual vale 12; "racing" dentro de "street racing" vale 5
            item.palavras?.split('|')?.forEach { k ->
                val kl = k.trim().lowercase()
                if (kl in palavras) pontos += 12
                else if (parciais.any { " $kl ".contains(it) }) pontos += 5
            }
            // 3) termos em português no nome ou na descrição (quem não tem palavras-chave entra com 2 termos achados)
            var termosAchados = 0
            if (termos.isNotEmpty()) {
                val plot = item.plot?.let { limpo(it) } ?: ""
                for (t in termos) {
                    val p = if (nome.contains(t)) 6 else if (plot.contains(t)) 3 else 0
                    if (p > 0) { pontos += p; termosAchados++ }
                }
            }
            // 4) gênero só desempata
            if (pontos > 0 && item.genre != null && generos.any { M3U.norm(item.genre).contains(it) }) pontos += 2
            if (pontos >= 10 || termosAchados >= 2) achados.add(Achado(item, pontos, nome))
        }
        return achados
            .sortedWith(compareByDescending<Achado> { it.pontos }.thenByDescending { it.item.rating?.toDoubleOrNull() ?: 0.0 })
            .distinctBy { Pair(it.item.kind, it.nome) }
            .take(MAX_RESULTADOS)
            .map { it.item }
    }
}
