package com.thenikito.streamplay

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Atualização pelo GitHub, como no app do PC: procura um StreamPLAY.apk na última release; se a versão for maior,
 * baixa (conferindo tamanho e SHA-256) e abre o instalador do Android.
 */
object Atualizador {
    private const val API = "https://api.github.com/repos/TheNIKITO/StreamPLAY/releases/latest"

    class Versao(val versao: String, val url: String, val tamanho: Long, val sha256: String?)

    val atual: String get() = BuildConfig.VERSION_NAME

    suspend fun procurar(): Versao? = withContext(Dispatchers.IO) {
        val c = URL(API).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "StreamPLAY-Android")
            val codigo = c.responseCode
            if (codigo == 404) return@withContext null
            if (codigo !in 200..299) throw ErroHttp(codigo)
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = j.optString("tag_name").trimStart('v', 'V')
            if (!maisNova(tag, atual)) return@withContext null
            val assets = j.optJSONArray("assets") ?: return@withContext null
            var escolhido: JSONObject? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val nome = a.optString("name")
                if (nome.equals("StreamPLAY.apk", true)) { escolhido = a; break }
                if (escolhido == null && nome.endsWith(".apk", true)) escolhido = a
            }
            val a = escolhido ?: return@withContext null
            val digest = a.optString("digest").removePrefix("sha256:").lowercase().takeIf { it.length == 64 }
            Versao(tag, a.getString("browser_download_url"), a.optLong("size"), digest)
        } finally {
            c.disconnect()
        }
    }

    fun maisNova(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    suspend fun baixar(dir: File, v: Versao, progresso: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val pasta = File(dir, "atualizacao").apply { mkdirs() }
        val f = File(pasta, "StreamPLAY-${v.versao}.apk")
        if (f.exists() && confere(f, v)) return@withContext f
        pasta.listFiles()?.forEach { it.delete() }
        var url = URL(v.url)
        var c: HttpURLConnection
        var voltas = 0
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "StreamPLAY-Android")
            val codigo = c.responseCode
            if (codigo in 300..399 && voltas++ < 6) {
                val destino = c.getHeaderField("Location")
                c.disconnect()
                url = URL(url, destino ?: throw IOException("redirecionamento sem destino"))
                continue
            }
            if (codigo !in 200..299) { c.disconnect(); throw ErroHttp(codigo) }
            break
        }
        try {
            val total = if (v.tamanho > 0) v.tamanho else c.contentLength.toLong()
            c.inputStream.use { ins ->
                f.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var lido = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        lido += n
                        if (total > 0) progresso((lido.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        if (!confere(f, v)) {
            f.delete()
            throw IOException("o arquivo baixado veio diferente do esperado")
        }
        f
    }

    private fun confere(f: File, v: Versao): Boolean {
        if (v.tamanho > 0 && f.length() != v.tamanho) return false
        if (v.sha256 == null) return true
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == v.sha256
    }

    /** Abre o instalador. Devolve false quando o Android pede antes a permissão de instalar apps. */
    fun instalar(act: Activity, f: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            try {
                act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName)))
            } catch (_: Exception) {
            }
            return false
        }
        val uri = if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(act, act.packageName + ".arquivos", f) else Uri.fromFile(f)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        act.startActivity(i)
        return true
    }
}
