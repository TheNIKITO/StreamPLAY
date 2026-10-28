package com.thenikito.streamplay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sincroniza o "continuar assistindo" e a Minha lista entre os aparelhos da mesma conta (PC, TV, celular) num
 * documento só no Firestore: acessos/{id}/sync/dados = { p: {chave: {pos, dur, at, show}}, f: {chave: {on, at}}, l, at }
 * (l = quando alguém usou "Limpar continuar assistindo"). Cada lado junta o que tem com o que está na nuvem — vale o
 * mais recente de cada item — e grava de volta. "at" = milissegundos UTC. Igual ao Sincronia.cs do PC.
 * A rede roda fora da linha principal; juntar no Store acontece na linha de quem chamou (a principal).
 */
object Sincronia {
    private const val MAX_PROGRESSO = 40   // na nuvem só os mais recentes (o aparelho guarda todos)
    private const val MAX_FAVORITOS = 300
    private val um = Mutex()

    /** Mudou progresso ou Minha lista aqui e a nuvem ainda não tem. */
    @Volatile var sujo = false

    class Nuvem(val p: Map<String, Progresso>, val f: Map<String, FavMarca>, val limpo: Long)

    /** Lê a nuvem e junta com o que está aqui. Devolve true se algo mudou neste aparelho (a tela atualiza). */
    suspend fun baixar(id: String): Boolean = um.withLock {
        val n = withContext(Dispatchers.IO) { ler(id) } ?: return@withLock false
        juntar(n)
    }

    /** Junta com a nuvem (para não apagar o que outro aparelho mandou) e grava. Só faz algo se estiver sujo. */
    suspend fun enviar(id: String): Boolean = um.withLock {
        if (!sujo) return@withLock false
        sujo = false
        val n = withContext(Dispatchers.IO) { ler(id) }
        if (n == null) { sujo = true; return@withLock false }
        val mudou = juntar(n)
        sujo = false   // a junção pode marcar "falta subir": é o que vai agora (mudança que chegar durante a gravação marca de novo)
        val corpo = montar(id)
        if (!withContext(Dispatchers.IO) { gravar(corpo) }) sujo = true
        mudou
    }

    // null = não deu para ler; vazio = ainda não existe nada na nuvem
    private fun ler(id: String): Nuvem? {
        val c = URL("${Conta.base}$id/sync/dados?key=${Conta.CHAVE_API}").openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 15000
            c.readTimeout = 15000
            when (c.responseCode) {
                404 -> Nuvem(emptyMap(), emptyMap(), 0)
                in 200..299 -> lerJson(c.inputStream.bufferedReader().use { it.readText() })
                else -> null
            }
        } catch (_: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    fun lerJson(json: String): Nuvem {
        val campos = JSONObject(json).optJSONObject("fields") ?: return Nuvem(emptyMap(), emptyMap(), 0)
        val p = HashMap<String, Progresso>()
        mapa(campos, "p") { k, f -> p[k] = Progresso(num(f, "pos"), num(f, "dur"), num(f, "at"), f.optJSONObject("show")?.optString("stringValue")?.ifEmpty { null }) }
        val fav = HashMap<String, FavMarca>()
        mapa(campos, "f") { k, f -> fav[k] = FavMarca(f.optJSONObject("on")?.optBoolean("booleanValue", false) ?: false, num(f, "at")) }
        return Nuvem(p, fav, num(campos, "l"))
    }

    private fun mapa(campos: JSONObject, nome: String, cada: (String, JSONObject) -> Unit) {
        val itens = campos.optJSONObject(nome)?.optJSONObject("mapValue")?.optJSONObject("fields") ?: return
        for (k in itens.keys()) itens.optJSONObject(k)?.optJSONObject("mapValue")?.optJSONObject("fields")?.let { cada(k, it) }
    }

    private fun num(f: JSONObject, n: String): Long = f.optJSONObject(n)?.optString("integerValue")?.toLongOrNull() ?: 0L

    /** Junta a nuvem no Store (vale o mais recente de cada item). Devolve true se algo daqui mudou. */
    fun juntar(n: Nuvem): Boolean {
        var mudou = false
        if (n.limpo > Store.progressoLimpo) {
            Store.progressoLimpo = n.limpo
            Store.progress.entries.removeAll { it.value.at <= n.limpo }
            mudou = true
        } else if (n.limpo < Store.progressoLimpo) sujo = true
        for ((k, c) in n.p) {
            if (c.at <= Store.progressoLimpo || c.dur <= 0) continue
            val aqui = Store.progress[k]
            if (aqui != null && aqui.at >= c.at) { if (aqui.at > c.at) sujo = true; continue }
            Store.progress[k] = c
            mudou = true
        }
        // tem aqui e não tem lá: a nuvem precisa receber
        if (Store.progress.entries.sortedByDescending { it.value.at }.take(MAX_PROGRESSO).any { it.key !in n.p }) sujo = true

        for ((k, c) in n.f) {
            val aqui = Store.favLog[k]
            if (aqui != null && aqui.at >= c.at) { if (aqui.at > c.at) sujo = true; continue }
            Store.favLog[k] = c
            val tem = Store.favorites.contains(k)
            if (c.on && !tem) { Store.favorites.add(0, k); mudou = true }
            else if (!c.on && tem) { Store.favorites.remove(k); mudou = true }
        }
        // favoritos daqui que a nuvem não conhece (inclusive os de antes da sincronização existir)
        if (Store.favorites.any { it !in n.f } || Store.favLog.keys.any { it !in n.f }) sujo = true
        if (mudou) Store.save()
        return mudou
    }

    // monta na linha principal (lê o Store); grava fora dela
    private fun montar(id: String): String {
        val p = JSONObject()
        for ((k, v) in Store.progress.entries.sortedByDescending { it.value.at }.take(MAX_PROGRESSO)) {
            val c = JSONObject()
                .put("pos", JSONObject().put("integerValue", v.pos.toString()))
                .put("dur", JSONObject().put("integerValue", v.dur.toString()))
                .put("at", JSONObject().put("integerValue", v.at.toString()))
            if (v.show != null) c.put("show", JSONObject().put("stringValue", v.show))
            p.put(k, JSONObject().put("mapValue", JSONObject().put("fields", c)))
        }
        // favoritos ligados (sem marca de hora = de antes da sincronização, valem como antigos) + os tirados mais recentes
        val marcas = HashMap(Store.favLog)
        for (k in Store.favorites) if (k !in marcas) marcas[k] = FavMarca(true, 1)
        val f = JSONObject()
        for ((k, v) in marcas.entries.sortedWith(compareByDescending<Map.Entry<String, FavMarca>> { it.value.on }.thenByDescending { it.value.at }).take(MAX_FAVORITOS))
            f.put(k, JSONObject().put("mapValue", JSONObject().put("fields", JSONObject()
                .put("on", JSONObject().put("booleanValue", v.on))
                .put("at", JSONObject().put("integerValue", v.at.toString())))))
        val campos = JSONObject()
            .put("p", JSONObject().put("mapValue", JSONObject().put("fields", p)))
            .put("f", JSONObject().put("mapValue", JSONObject().put("fields", f)))
            .put("l", JSONObject().put("integerValue", Store.progressoLimpo.toString()))
        val escrita = JSONObject()
            .put("update", JSONObject().put("name", "projects/${Conta.PROJETO}/databases/(default)/documents/acessos/$id/sync/dados").put("fields", campos))
            .put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "at").put("setToServerValue", "REQUEST_TIME")))
        return JSONObject().put("writes", JSONArray().put(escrita)).toString()
    }

    private fun gravar(corpo: String): Boolean {
        val raiz = Conta.base.trimEnd('/').substringBeforeLast('/')   // .../documents
        val c = URL("$raiz:commit?key=${Conta.CHAVE_API}").openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(corpo.toByteArray(Charsets.UTF_8)) }
            c.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            c.disconnect()
        }
    }
}
