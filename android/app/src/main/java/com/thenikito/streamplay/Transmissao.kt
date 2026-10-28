package com.thenikito.streamplay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.videolan.libvlc.RendererDiscoverer
import org.videolan.libvlc.RendererItem

/**
 * Chromecast (só no celular/tablet; na TV não faz sentido): o próprio VLC acha os aparelhos na rede (mDNS) e, com o
 * aparelho escolhido no player, manda o vídeo para a TV (convertendo no celular se o formato não servir). Igual ao
 * Transmissao.cs do PC. A busca começa quando a pessoa abre o painel de transmitir e para quando o player fecha.
 */
object Transmissao {
    private var busca: RendererDiscoverer? = null
    private var multicast: WifiManager.MulticastLock? = null
    private val main = Handler(Looper.getMainLooper())

    /** Aparelhos achados até agora (na ordem em que apareceram). */
    val aparelhos = mutableStateListOf<RendererItem>()

    /** Para onde o player está transmitindo (null = no próprio celular). Vale até a pessoa parar ou o app fechar. */
    var atual by mutableStateOf<RendererItem?>(null)
        private set

    val buscando get() = busca != null

    fun nome(a: RendererItem): String = a.displayName?.takeIf { it.isNotBlank() } ?: a.name ?: "Chromecast"

    fun mesmo(a: RendererItem, b: RendererItem) = a.name == b.name && a.type == b.type

    fun ehOAtual(a: RendererItem) = atual?.let { mesmo(it, a) } ?: false

    fun definirAtual(a: RendererItem?) {
        if (a != null) a.retain()   // o item continua valendo mesmo depois que a busca para
        atual?.release()
        atual = a
    }

    fun iniciar(ctx: Context) {
        if (busca != null) return
        val lib = Vlc.lib ?: return
        try {
            // sem essa trava o Android joga fora as respostas do mDNS (multicast) para economizar bateria
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicast = wifi?.createMulticastLock("StreamPLAY-chromecast")?.apply { setReferenceCounted(false); acquire() }
            val nome = RendererDiscoverer.list(lib).firstOrNull()?.name ?: "microdns_renderer"
            val b = RendererDiscoverer(lib, nome)
            b.setEventListener { e ->
                val item = e.item ?: return@setEventListener
                when (e.type) {
                    RendererDiscoverer.Event.ItemAdded -> {
                        item.retain()   // o evento solta o item quando termina: guardamos o nosso
                        main.post { if (aparelhos.none { mesmo(it, item) }) aparelhos.add(item) else item.release() }
                    }
                    RendererDiscoverer.Event.ItemDeleted -> {
                        val nomeItem = item.name
                        val tipo = item.type
                        main.post {
                            aparelhos.filter { it.name == nomeItem && it.type == tipo }.forEach { aparelhos.remove(it); it.release() }
                        }
                    }
                }
            }
            if (!b.start()) { b.release(); pararMulticast(); return }
            busca = b
        } catch (_: Exception) {
            pararMulticast()
        }
    }

    /** O player fechou: para de procurar (a escolha atual continua guardada para o próximo título). */
    fun parar() {
        val b = busca ?: return
        busca = null
        try { b.setEventListener(null); b.stop(); b.release() } catch (_: Exception) { }
        aparelhos.forEach { try { it.release() } catch (_: Exception) { } }
        aparelhos.clear()
        pararMulticast()
    }

    private fun pararMulticast() {
        try { multicast?.release() } catch (_: Exception) { }
        multicast = null
    }

    // ---------- serviço: mantém a transmissão com a tela apagada ----------

    fun avisarInicio(ctx: Context, aparelho: String) {
        val i = Intent(ctx, ServicoTransmissao::class.java).putExtra("aparelho", aparelho)
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (_: Exception) { }
    }

    fun avisarFim(ctx: Context) {
        try { ctx.stopService(Intent(ctx, ServicoTransmissao::class.java)) } catch (_: Exception) { }
    }
}

/**
 * Enquanto transmite, é o celular que busca o vídeo e manda para a TV. Com a tela apagada o Android pararia o app;
 * este serviço (com o aviso "Transmitindo para ..." na barra) mantém o processador e o Wi-Fi acordados.
 */
class ServicoTransmissao : Service() {
    private var cpu: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val aparelho = intent?.getStringExtra("aparelho") ?: "a TV"
        val gerente = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && gerente.getNotificationChannel(CANAL) == null)
            gerente.createNotificationChannel(NotificationChannel(CANAL, "Transmitindo para a TV", NotificationManager.IMPORTANCE_LOW))
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0),
        )
        val aviso: Notification = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Transmitindo para $aparelho")
            .setContentText("Deixe o StreamPLAY aberto enquanto a TV mostra o vídeo.")
            .setContentIntent(abrir)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        ServiceCompat.startForeground(
            this, 1, aviso,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        if (cpu == null) cpu = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreamPLAY:transmitindo").apply { setReferenceCounted(false); acquire() }
        if (wifi == null) {
            val w = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifi = w.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "StreamPLAY:transmitindo").apply { setReferenceCounted(false); acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { cpu?.release() } catch (_: Exception) { }
        try { wifi?.release() } catch (_: Exception) { }
        cpu = null
        wifi = null
        super.onDestroy()
    }

    companion object {
        private const val CANAL = "transmitir"
    }
}
