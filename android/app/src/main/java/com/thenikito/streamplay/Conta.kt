package com.thenikito.streamplay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.NumberFormat
import java.util.Locale

/**
 * Login dos clientes pelo painel PLAYGER (Firebase), igual ao Conta.cs do PC. Para cada cliente, o painel grava no
 * Firestore o documento acessos/{sha256("playger-acesso|login|senha")}. O app só encontra o documento sabendo login
 * e senha, e o documento diz se a conta está ativa e a partir de que dia o acesso bloqueia por falta de pagamento.
 * A data usada é a do servidor.
 */
object Conta {
    private const val PROJETO = "playger-svect"
    private const val CHAVE_API = "AIzaSyDhFrw8OFoCfSqptvuW1irLmpPgjegBLSo" // chave pública do app web do Firebase (não é segredo)
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
    )

    class Resultado(val estado: Estado, val titulo: String, val mensagem: String, val acesso: Acesso?, val hoje: Int)

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
            try {
                avaliar(ler(c.inputStream.bufferedReader().use { it.readText() }), hoje)
            } catch (_: Exception) {
                Resultado(Estado.SemConexao, "Resposta inválida", "Não entendi a resposta do servidor. Tente de novo daqui a pouco.", null, hoje)
            }
        } finally {
            c.disconnect()
        }
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
