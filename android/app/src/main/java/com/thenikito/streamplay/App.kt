package com.thenikito.streamplay

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.videolan.libvlc.LibVLC

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Store.load(this)
        Vlc.iniciar(this)
    }

    // capas e logos ficam guardadas no aparelho: abrir de novo não baixa tudo outra vez
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("imagens")).maxSizeBytes(200L * 1024 * 1024).build() }
        .respectCacheHeaders(false)
        .crossfade(true)
        .build()
}

/** O motor do VLC é preparado em segundo plano assim que o app abre (como no PC). */
object Vlc {
    private var tarefa: Deferred<LibVLC>? = null

    @Volatile
    var lib: LibVLC? = null
        private set

    fun iniciar(ctx: Context) {
        if (tarefa != null) return
        val app = ctx.applicationContext
        tarefa = CoroutineScope(SupervisorJob() + Dispatchers.Default).async {
            LibVLC(
                app,
                arrayListOf(
                    "--no-video-title-show", "--no-stats", "--no-snapshot-preview", "--no-avcodec-corrupted",
                    "--freetype-rel-fontsize=16", "--freetype-outline-thickness=4",
                ),
            ).also { lib = it }
        }
    }

    suspend fun pronto(): LibVLC = tarefa!!.await()

    val versao: String?
        get() = if (lib == null) null else runCatching { LibVLC.version().split(' ').firstOrNull() }.getOrNull()
}
