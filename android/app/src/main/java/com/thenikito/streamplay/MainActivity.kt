package com.thenikito.streamplay

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.thenikito.streamplay.ui.Raiz
import com.thenikito.streamplay.ui.StreamPlayTema
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(0xFF141414.toInt()),
        )
        // só na versão de teste: conta falsa e lista de teste servidas pelo PC
        if (BuildConfig.DEBUG) {
            intent.getStringExtra("playger_url")?.let { Conta.base = it }
            intent.getStringExtra("lista")?.let { Store.source = it; Store.save() }
        }
        Estado.iniciar(this)
        setContent { StreamPlayTema { Raiz() } }

        // com o app na frente: confere conta, lista e atualização de tempos em tempos (como os timers do PC)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Estado.aoVoltar()
                while (true) {
                    delay(60_000)
                    Estado.periodico()
                }
            }
        }
    }
}
