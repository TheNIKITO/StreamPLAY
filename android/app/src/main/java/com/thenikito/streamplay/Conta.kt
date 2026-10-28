package com.thenikito.streamplay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Login dos clientes pelo painel PLAYGER (Firebase), igual ao Conta.cs do PC. Para cada cliente, o painel grava no
 * Firestore o documento acessos/{sha256("playger-acesso|login|senha")}. O app só encontra o documento sabendo login
 * e senha, e o documento diz se a conta está ativa e a partir de que dia o acesso bloqueia por falta de pagamento.
 * A data usada é a do servidor.
 */
object Conta {
    const val PROJETO = "playger-svect"
    const val CHAVE_API = "AIzaSyDhFrw8OFoCfSqptvuW1irLmpPgjegBLSo" // chave pública do app web do Firebase (não é segredo)
    private const val BASE = "https://firestore.googleapis.com/v1/projects/$PROJETO/databases/(default)/documents/acessos/"

    /** Só muda nos testes (versão de depuração aberta com o extra "playger_url": servidor falso no PC). */
    var base = BASE

    enum class Estado { Liberado, Bloqueado, Invalido, SemConexao }

    class Acesso(
        val login: String?,
        val nome: String?,
        val plano: String?,
        val negocio: String?,
        val pix: String?,
        val ativo: Boolean,
        val manual: Boolean,
        val bloqueiaEm: Int?,
        val liberadoAte: Int?,
        val faturaVenc: Int?,
        val faturaValor: Double?,
        /** Limite de aparelhos (0 = sem limite). */
        val limite: Int = 0,
    )

    /** [aparelhos]: bloqueado porque o limite de aparelhos está cheio (não é falta de pagamento). */
    class Resultado(
        val estado: Estado, val titulo: String, val mensagem: String, val acesso: Acesso?, val hoje: Int, val aparelhos: Boolean = false,
    )

    fun idAcesso(login: String?, senha: String): String {
        val b = MessageDigest.getInstance("SHA-256")
            .digest(("playger-acesso|" + (login ?: "").trim().lowercase(Locale.ROOT) + "|" + senha).toByteArray(Charsets.UTF_8))
        return b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    suspend fun verificar(id: String): Resultado = withContext(Dispatchers.IO) {
        val c: HttpURLConnection
        val codigo: Int
        try {
            c = URL("$base$id?key=$CHAVE_API").openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 15000
            codigo = c.responseCode
        } catch (_: IOException) {
            return@withContext Resultado(
                Estado.SemConexao, "Sem conexão", "Não consegui falar com o servidor. Verifique a internet e tente de novo.",
                null, Dia.hoje(),
            )
        }
        try {
            val hoje = if (c.date > 0) Dia.de(c.date) else Dia.hoje()
            if (codigo == 404)
                return@withContext Resultado(Estado.Invalido, "Login ou senha incorretos", "Confira o login e a senha que você recebeu.", null, hoje)
            if (codigo !in 200..299)
                return@withContext Resultado(
                    Estado.SemConexao, "Servidor indisponível",
                    "O servidor não respondeu direito (erro $codigo). Tente de novo daqui a pouco.", null, hoje,
                )
            val r = try {
                avaliar(ler(c.inputStream.bufferedReader().use { it.readText() }), hoje)
            } catch (_: Exception) {
                return@withContext Resultado(Estado.SemConexao, "Resposta inválida", "Não entendi a resposta do servidor. Tente de novo daqui a pouco.", null, hoje)
            }
            if (r.estado == Estado.Liberado) conferirAparelhos(id, r) else r
        } finally {
            c.disconnect()
        }
    }

    // ---------- limite de aparelhos (igual ao Conta.cs do PC) ----------
    // Cada aparelho se registra em acessos/{id}/aparelhos/{aid} com um nome ("TV – Xiaomi MiBox") e a hora do servidor.
    // Quem não aparece há 30 dias deixa de contar. Com o limite cheio, um aparelho novo não entra até o painel remover
    // um antigo. Se a conferência falhar (sem internet, servidor fora), o aparelho não é barrado por isso.

    private const val DIA = 24 * 60 * 60 * 1000L
    private const val INATIVO = 30 * DIA
    private const val RENOVAR_CADA = DIA / 2
    private const val CONFERIR_CADA = DIA / 24   // a conta é conferida a cada 15 min; os aparelhos, menos
    private var aparelhosOk: String? = null
    private var aparelhosEm = 0L

    /** Nome e tipo deste aparelho no painel (definidos ao abrir o app). */
    var nomeAparelho = "Celular"
    var tipoAparelho = "celular"

    fun definirAparelho(tv: Boolean, fabricante: String?, modelo: String?) {
        tipoAparelho = if (tv) "tv" else "celular"
        val m = (modelo ?: "").trim()
        val f = (fabricante ?: "").trim().replaceFirstChar { it.uppercase() }
        val nome = if (f.isEmpty() || m.startsWith(f, ignoreCase = true)) m else "$f $m".trim()
        nomeAparelho = ((if (tv) "TV" else "Celular") + if (nome.isNotBlank()) " – $nome" else "").take(80)
    }

    class Aparelho(val id: String, val nome: String, val visto: Long)

    private fun conferirAparelhos(id: String, r: Resultado): Resultado {
        if (aparelhosOk == id && System.currentTimeMillis() - aparelhosEm < CONFERIR_CADA) return r
        try {
            val c = URL("$base$id/aparelhos?pageSize=300&key=$CHAVE_API").openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 15000
            val todos: List<Aparelho>
            val agora: Long
            try {
                if (c.responseCode !in 200..299) return r
                agora = if (c.date > 0) c.date else System.currentTimeMillis()
                todos = lerAparelhos(c.inputStream.bufferedReader().use { it.readText() })
            } finally {
                c.disconnect()
            }
            val eu = Store.meuAparelho()
            val meu = todos.firstOrNull { it.id == eu }
            val ativos = todos.filter { agora - it.visto < INATIVO }
            val limite = r.acesso?.limite ?: 0
            if (limite > 0 && ativos.none { it.id == eu } && ativos.size >= limite) {
                val negocio = r.acesso?.negocio
                val contato = if (negocio.isNullOrBlank()) "o suporte" else negocio
                fun qtd(n: Int) = if (n == 1) "1 aparelho" else "$n aparelhos"
                val nomes = ativos.sortedByDescending { it.visto }.joinToString(", ") { it.nome }
                aparelhosOk = null
                return Resultado(
                    Estado.Bloqueado, "Limite de aparelhos atingido",
                    "Este login já está em uso em ${qtd(ativos.size)} ($nomes) e o seu plano permite ${qtd(limite)}. " +
                        "Para assistir aqui, peça para $contato liberar um aparelho.",
                    r.acesso, r.hoje, aparelhos = true,
                )
            }
            if (meu == null || agora - meu.visto > RENOVAR_CADA || meu.nome != nomeAparelho)
                if (!registrar(id, eu)) return r   // não conseguiu anotar: confere de novo na próxima
            aparelhosOk = id
            aparelhosEm = System.currentTimeMillis()
        } catch (_: Exception) {
        }
        return r
    }

    // Grava o registro deste aparelho com a hora do servidor (as regras do Firestore só aceitam assim).
    // soSeExiste: só renova um registro que já existe (o sinal de online não recria um aparelho removido no painel).
    // comOnline = false: sem o campo "online" (regras antigas do PLAYGER recusam o campo com 403; aí grava como antes).
    private fun registrar(id: String, eu: String, online: Boolean = true, soSeExiste: Boolean = false, comOnline: Boolean = true): Boolean {
        val raiz = base.trimEnd('/').substringBeforeLast('/')   // .../documents
        val campos = JSONObject()
            .put("nome", JSONObject().put("stringValue", nomeAparelho))
            .put("tipo", JSONObject().put("stringValue", tipoAparelho))
        if (comOnline) campos.put("online", JSONObject().put("booleanValue", online))
        val escrita = JSONObject()
            .put("update", JSONObject().put("name", "projects/$PROJETO/databases/(default)/documents/acessos/$id/aparelhos/$eu").put("fields", campos))
            .put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "visto").put("setToServerValue", "REQUEST_TIME")))
        if (soSeExiste) escrita.put("currentDocument", JSONObject().put("exists", true))
        val corpo = JSONObject().put("writes", JSONArray().put(escrita)).toString().toByteArray(Charsets.UTF_8)
        val c = URL("$raiz:commit?key=$CHAVE_API").openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(corpo) }
            val codigo = c.responseCode
            if (codigo == 403 && comOnline) registrar(id, eu, online, soSeExiste, comOnline = false) else codigo in 200..299
        } finally {
            c.disconnect()
        }
    }

    // ---------- online (igual ao Conta.cs do PC) ----------
    // Com o app na frente, o aparelho avisa a cada 5 min que está online (o painel mostra na aba "Online");
    // ao sair do app, avisa que saiu. Quem para de avisar (caiu, sem internet) vira offline sozinho no painel.

    /** Avisa o painel que este aparelho está (ou deixou de estar) com o app aberto. Bloqueia: chame fora da thread principal. */
    fun sinal(id: String, online: Boolean) {
        try {
            registrar(id, Store.meuAparelho(), online, soSeExiste = true)
        } catch (_: Exception) {
        }
    }

    fun lerAparelhos(json: String): List<Aparelho> {
        val docs = JSONObject(json).optJSONArray("documents") ?: return emptyList()
        val l = ArrayList<Aparelho>()
        for (i in 0 until docs.length()) {
            val d = docs.getJSONObject(i)
            val f = d.optJSONObject("fields") ?: continue
            val visto = f.optJSONObject("visto")?.optString("timestampValue")?.let { instante(it) } ?: 0L
            l.add(Aparelho(d.getString("name").substringAfterLast('/'), texto(f, "nome") ?: "Aparelho", visto))
        }
        return l
    }

    private val INSTANTE_RX = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?Z$""")

    /** "2026-10-27T18:45:17.681Z" em milissegundos (sem java.time, que só existe a partir do Android 8). */
    fun instante(s: String): Long? {
        val m = INSTANTE_RX.find(s) ?: return null
        val g = m.groupValues
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
        val ms = if (g[7].isEmpty()) 0 else (g[7] + "00").substring(0, 3).toInt()
        return c.timeInMillis + ms
    }

    /** Mesma regra do painel: desativado, bloqueio manual ou fatura vencida além da tolerância. */
    fun avaliar(a: Acesso, hoje: Int): Resultado {
        val contato = if (a.negocio.isNullOrBlank()) "o suporte" else a.negocio
        if (!a.ativo) return Resultado(Estado.Bloqueado, "Conta desativada", "Esta conta está desativada. Fale com $contato.", a, hoje)
        if (a.manual) return Resultado(Estado.Bloqueado, "Acesso bloqueado", "Seu acesso está bloqueado. Fale com $contato.", a, hoje)
        val liberado = a.liberadoAte != null && a.liberadoAte >= hoje
        if (a.bloqueiaEm != null && hoje >= a.bloqueiaEm && !liberado) {
            val msg = (if (a.faturaVenc != null) "Sua fatura de ${dinheiro(a.faturaValor)} venceu em ${Dia.fmt(a.faturaVenc)}." else "Há uma fatura em aberto.") +
                " Assim que o pagamento for confirmado, o acesso volta na hora."
            return Resultado(Estado.Bloqueado, "Acesso bloqueado por falta de pagamento", msg, a, hoje)
        }
        return Resultado(Estado.Liberado, "", "", a, hoje)
    }

    /** Lembrete de vencimento para mostrar depois de entrar (ou null). */
    fun aviso(r: Resultado?): String? {
        val v = r?.acesso?.faturaVenc ?: return null
        val valor = dinheiro(r.acesso.faturaValor)
        val d = Dia.dias(r.hoje, v)
        return when {
            d < 0 -> "Sua fatura de $valor venceu em ${Dia.fmtCurto(v)}. Pague para não perder o acesso."
            d == 0 -> "Sua fatura de $valor vence hoje."
            d <= 3 -> "Sua fatura de $valor vence em $d dia${if (d > 1) "s" else ""} (${Dia.fmtCurto(v)})."
            else -> null
        }
    }

    fun dinheiro(v: Double?): String = NumberFormat.getCurrencyInstance(Locale("pt", "BR")).format(v ?: 0.0)

    // ---------- leitura do documento no formato da API REST do Firestore ----------

    fun ler(json: String): Acesso {
        val f = JSONObject(json).getJSONObject("fields")
        val fatura = f.optJSONObject("fatura")?.optJSONObject("mapValue")?.optJSONObject("fields")
        return Acesso(
            login = texto(f, "login"),
            nome = texto(f, "nome"),
            plano = texto(f, "plano"),
            negocio = texto(f, "negocio"),
            pix = texto(f, "pix"),
            ativo = bool(f, "ativo"),
            manual = bool(f, "manual"),
            bloqueiaEm = Dia.parse(texto(f, "bloqueiaEm")),
            liberadoAte = Dia.parse(texto(f, "liberadoAte")),
            faturaVenc = fatura?.let { Dia.parse(texto(it, "venc")) },
            faturaValor = fatura?.let { numero(it, "valor") },
            limite = numero(f, "limite")?.toInt() ?: 0,
        )
    }

    private fun texto(f: JSONObject, n: String): String? = f.optJSONObject(n)?.let { if (it.has("stringValue")) it.optString("stringValue") else null }
    private fun bool(f: JSONObject, n: String): Boolean = f.optJSONObject(n)?.optBoolean("booleanValue", false) ?: false
    private fun numero(f: JSONObject, n: String): Double? {
        val v = f.optJSONObject(n) ?: return null
        if (v.has("doubleValue")) return v.optDouble("doubleValue")
        if (v.has("integerValue")) return v.optString("integerValue").toDoubleOrNull()
        return null
    }
}
