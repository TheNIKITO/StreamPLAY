package com.thenikito.streamplay

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.videolan.libvlc.RendererItem
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Chromecast preso numa transmissão antiga: quando o celular some de repente (app fechado à força, bateria acabou),
 * a TV fica com o receptor do VLC aberto esperando um vídeo que não vem mais, e às vezes só volta reiniciando a TV.
 * Antes da primeira transmissão para cada TV em cada abertura do app (e de novo depois de uma TV que falhou), o app
 * acha a TV na rede (mDNS) e, se o receptor padrão (o que o VLC usa) estiver aberto, pede para a TV fechá-lo
 * (protocolo Cast v2: mensagens protobuf numa conexão TLS na porta 8009). Não achou ou demorou: segue sem limpar.
 */
object SessaoTv {
    private const val LOG = "StreamPLAY-TV"
    private const val RECEPTOR_PADRAO = "CC1AD845"   // Default Media Receiver
    private const val NS_CONEXAO = "urn:x-cast:com.google.cast.tp.connection"
    private const val NS_RECEPTOR = "urn:x-cast:com.google.cast.receiver"
    private val conferidas = HashSet<String>()

    @Synchronized fun precisa(a: RendererItem) = Transmissao.nome(a) !in conferidas

    /** A TV falhou: na próxima transmissão para ela confere de novo. */
    @Synchronized fun esquecer(a: RendererItem) { conferidas.remove(Transmissao.nome(a)) }

    /** Fora da linha principal (leva até ~5 s). True se fechou uma sessão antiga. */
    fun limpar(ctx: Context, a: RendererItem): Boolean {
        val nomes = listOfNotNull(a.displayName, a.name).filter { it.isNotBlank() }.toSet()
        synchronized(this) { conferidas.add(Transmissao.nome(a)) }
        val tv = try { achar(ctx.applicationContext, nomes) } catch (e: Exception) { null }
        if (tv == null) { Log.i(LOG, "TV $nomes não achada no mDNS: segue sem limpar"); return false }
        return try {
            fecharReceptor(tv.first, tv.second).also { Log.i(LOG, "TV $nomes em ${tv.first.hostAddress}:${tv.second}: sessão antiga fechada = $it") }
        } catch (e: Exception) {
            Log.i(LOG, "TV $nomes: não deu para conferir (${e.message})")
            false
        }
    }

    // ---------- achar a TV na rede ----------

    @Suppress("DEPRECATION")   // resolveService/host: os substitutos só existem do Android 14 em diante
    private fun achar(ctx: Context, nomes: Set<String>): Pair<InetAddress, Int>? {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        val achados = LinkedBlockingQueue<NsdServiceInfo>()
        val busca = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(s: NsdServiceInfo) { achados.offer(s) }
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onDiscoveryStarted(tipo: String) {}
            override fun onDiscoveryStopped(tipo: String) {}
            override fun onStartDiscoveryFailed(tipo: String, erro: Int) {}
            override fun onStopDiscoveryFailed(tipo: String, erro: Int) {}
        }
        nsd.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, busca)
        try {
            val fim = SystemClock.elapsedRealtime() + 3000
            while (true) {
                val resta = fim - SystemClock.elapsedRealtime()
                if (resta <= 0) return null
                val s = achados.poll(resta, TimeUnit.MILLISECONDS) ?: return null
                val r = resolver(nsd, s) ?: continue
                val fn = r.attributes["fn"]?.let { String(it, Charsets.UTF_8) }
                if (fn in nomes || r.serviceName in nomes) return r.host to r.port
            }
        } finally {
            try { nsd.stopServiceDiscovery(busca) } catch (_: Exception) { }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolver(nsd: NsdManager, s: NsdServiceInfo): NsdServiceInfo? {
        val pronto = CountDownLatch(1)
        var achado: NsdServiceInfo? = null
        nsd.resolveService(s, object : NsdManager.ResolveListener {
            override fun onServiceResolved(r: NsdServiceInfo) { achado = r; pronto.countDown() }
            override fun onResolveFailed(r: NsdServiceInfo, erro: Int) { pronto.countDown() }
        })
        pronto.await(1500, TimeUnit.MILLISECONDS)
        return achado
    }

    // ---------- falar com a TV (Cast v2) ----------

    // O Chromecast usa um certificado feito por ele mesmo: não há como validar. Só vai um pedido de "fechar", sem senha.
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val aceitaTudo = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<X509Certificate>?, t: String?) {}
        override fun checkServerTrusted(c: Array<X509Certificate>?, t: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun fecharReceptor(host: InetAddress, porta: Int): Boolean {
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(aceitaTudo), SecureRandom()) }
        (tls.socketFactory.createSocket() as SSLSocket).use { s ->
            s.connect(InetSocketAddress(host, porta), 2500)
            s.soTimeout = 2500
            s.startHandshake()
            val saida = DataOutputStream(BufferedOutputStream(s.outputStream))
            val entrada = DataInputStream(BufferedInputStream(s.inputStream))
            fun mandar(ns: String, json: JSONObject) {
                val m = mensagem(ns, json.toString())
                saida.writeInt(m.size)
                saida.write(m)
                saida.flush()
            }
            mandar(NS_CONEXAO, JSONObject().put("type", "CONNECT"))
            mandar(NS_RECEPTOR, JSONObject().put("type", "GET_STATUS").put("requestId", 1))
            val fim = SystemClock.elapsedRealtime() + 2500
            while (SystemClock.elapsedRealtime() < fim) {
                val tamanho = entrada.readInt()
                if (tamanho !in 1..65536) return false
                val dados = ByteArray(tamanho).also { entrada.readFully(it) }
                val (ns, texto) = ler(dados) ?: continue
                if (ns != NS_RECEPTOR) continue   // PING e outros avisos da TV
                val j = JSONObject(texto)
                if (j.optString("type") != "RECEIVER_STATUS") continue
                val apps = j.optJSONObject("status")?.optJSONArray("applications") ?: return false
                for (i in 0 until apps.length()) {
                    val app = apps.getJSONObject(i)
                    if (app.optString("appId") != RECEPTOR_PADRAO) continue   // YouTube, Netflix etc.: não mexe
                    mandar(NS_RECEPTOR, JSONObject().put("type", "STOP").put("requestId", 2).put("sessionId", app.optString("sessionId")))
                    mandar(NS_CONEXAO, JSONObject().put("type", "CLOSE"))
                    return true
                }
                return false
            }
            return false
        }
    }

    /** CastMessage (protobuf): versão, origem, destino, namespace, tipo texto e o JSON. */
    private fun mensagem(ns: String, json: String): ByteArray {
        val b = ByteArrayOutputStream()
        fun varint(v: Int) {
            var x = v
            while (x >= 0x80) { b.write((x and 0x7F) or 0x80); x = x ushr 7 }
            b.write(x)
        }
        fun texto(campo: Int, s: String) {
            val d = s.toByteArray(Charsets.UTF_8)
            varint((campo shl 3) or 2)
            varint(d.size)
            b.write(d)
        }
        varint(1 shl 3); varint(0)   // protocol_version = CASTV2_1_0
        texto(2, "sender-0")
        texto(3, "receiver-0")
        texto(4, ns)
        varint(5 shl 3); varint(0)   // payload_type = STRING
        texto(6, json)
        return b.toByteArray()
    }

    /** Lê o namespace e o JSON de uma CastMessage; null se vier outra coisa. */
    private fun ler(d: ByteArray): Pair<String, String>? {
        var i = 0
        var ns: String? = null
        var texto: String? = null
        fun varint(): Long {
            var r = 0L
            var desloca = 0
            while (true) {
                val b = d[i++].toInt() and 0xFF
                r = r or ((b and 0x7F).toLong() shl desloca)
                if (b < 0x80) return r
                desloca += 7
            }
        }
        try {
            while (i < d.size) {
                val chave = varint().toInt()
                when (chave and 7) {
                    0 -> varint()
                    2 -> {
                        val n = varint().toInt()
                        val s = String(d, i, n, Charsets.UTF_8)
                        i += n
                        when (chave ushr 3) { 4 -> ns = s; 6 -> texto = s }
                    }
                    else -> return null
                }
            }
        } catch (_: Exception) {
            return null
        }
        return if (ns != null && texto != null) ns!! to texto!! else null
    }
}
