package com.thenikito.streamplay

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class Progresso(val pos: Long, val dur: Long, val at: Long, val show: String?) {
    val fraction: Double get() = if (dur > 0) (pos.toDouble() / dur).coerceIn(0.0, 1.0) else 0.0
    val finished: Boolean get() = dur > 0 && pos >= dur * 0.95
}

/** Dados do app no aparelho (favoritos, progresso, lista escolhida, conta). Igual ao dados.json do PC. */
object Store {
    private var arquivo: File? = null
    private val gravador = Executors.newSingleThreadExecutor()

    var source: String? = null
    var autoNext = true
    var caching = 1500
    var tab = "movies"
    val favorites = ArrayList<String>()
    val progress = HashMap<String, Progresso>()
    val recent = ArrayList<String>()

    // Conta do PLAYGER: guarda só o id do acesso (hash de login + senha), nunca a senha.
    var login: String? = null
    var acessoId: String? = null
    var contaOk = 0L
    /** Código aleatório deste aparelho no limite de aparelhos do PLAYGER (criado na primeira vez). */
    var aparelho: String? = null

    /** De qual lista é a cópia salva no aparelho (para abrir sem internet). */
    var listaSalva: String? = null

    /** Muda a cada gravação: as telas que mostram favoritos e progresso leem isto para se atualizar. */
    val versao = mutableIntStateOf(0)

    fun load(ctx: Context) {
        val f = File(ctx.filesDir, "dados.json")
        arquivo = f
        try {
            if (f.exists()) ler(JSONObject(f.readText()))
        } catch (_: Exception) {
        }
    }

    private fun ler(j: JSONObject) {
        source = j.optString("source").ifEmpty { null }
        autoNext = j.optBoolean("autoNext", true)
        caching = j.optInt("caching", 1500)
        tab = j.optString("tab", "movies")
        login = j.optString("login").ifEmpty { null }
        acessoId = j.optString("acessoId").ifEmpty { null }
        contaOk = j.optLong("contaOk", 0)
        aparelho = j.optString("aparelho").ifEmpty { null }
        listaSalva = j.optString("listaSalva").ifEmpty { null }
        lista(j.optJSONArray("favorites"), favorites)
        lista(j.optJSONArray("recent"), recent)
        val p = j.optJSONObject("progress")
        if (p != null) for (k in p.keys()) {
            val o = p.optJSONObject(k) ?: continue
            progress[k] = Progresso(o.optLong("pos"), o.optLong("dur"), o.optLong("at"), o.optString("show").ifEmpty { null })
        }
    }

    private fun lista(a: JSONArray?, out: ArrayList<String>) {
        if (a == null) return
        for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotEmpty() }?.let { out.add(it) }
    }

    fun save() {
        versao.intValue++
        val f = arquivo ?: return
        val texto = json().toString()
        gravador.execute {
            try {
                val tmp = File(f.path + ".tmp")
                tmp.writeText(texto)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            } catch (_: Exception) {
            }
        }
    }

    private fun json() = JSONObject().apply {
        put("source", source ?: "")
        put("autoNext", autoNext)
        put("caching", caching)
        put("tab", tab)
        put("login", login ?: "")
        put("acessoId", acessoId ?: "")
        put("contaOk", contaOk)
        put("aparelho", aparelho ?: "")
        put("listaSalva", listaSalva ?: "")
        put("favorites", JSONArray(favorites))
        put("recent", JSONArray(recent))
        val p = JSONObject()
        for ((k, v) in progress) p.put(k, JSONObject().apply {
            put("pos", v.pos)
            put("dur", v.dur)
            put("at", v.at)
            if (v.show != null) put("show", v.show)
        })
        put("progress", p)
    }

    fun meuAparelho(): String {
        aparelho?.takeIf { it.length == 32 }?.let { return it }
        val b = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        return b.joinToString("") { "%02x".format(it.toInt() and 0xff) }.also { aparelho = it; save() }
    }

    fun isFav(key: String) = favorites.contains(key)

    fun toggleFav(key: String): Boolean {
        val on = !favorites.remove(key)
        if (on) favorites.add(0, key)
        save()
        return on
    }

    fun getProgress(key: String?): Progresso? = if (key == null) null else progress[key]

    fun watched(key: String?): Double {
        val p = getProgress(key)
        return if (p == null || p.finished) 0.0 else p.fraction
    }

    fun setProgress(key: String, pos: Long, dur: Long, show: String?, save: Boolean = true) {
        if (dur <= 0) return
        progress[key] = Progresso(pos, dur, System.currentTimeMillis(), show)
        if (progress.size > 600)
            progress.entries.sortedBy { it.value.at }.take(100).map { it.key }.forEach { progress.remove(it) }
        if (save) save()
    }

    fun addRecent(key: String) {
        recent.remove(key)
        recent.add(0, key)
        while (recent.size > 30) recent.removeAt(recent.size - 1)
        save()
    }
}
