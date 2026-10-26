package com.thenikito.streamplay.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.material3.MaterialTheme
import kotlin.math.abs
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object Cor {
    val Fundo = Color(0xFF141414)
    val Painel = Color(0xFF181818)
    val Cartao = Color(0xFF2A2A2A)
    val Hover = Color(0xFF333333)
    val Sub = Color(0xFFB3B3B3)
    val Dim = Color(0xFF8C8C8C)
    val Vermelho = Color(0xFFE50914)
    val Linha = Color(0xFF3A3A3A)
    val Link = Color(0xFF54B9C5)
    val Verde = Color(0xFF46D369)
    val Texto = Color(0xFFE5E5E5)
    val Cinza = Color(0x996D6D6E)
}

val LocalTv = staticCompositionLocalOf { false }

fun ehTv(ctx: Context): Boolean {
    val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StreamPlayTema(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val tv = remember { ehTv(ctx) }
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cor.Vermelho,
            onPrimary = Color.White,
            secondary = Color.White,
            background = Cor.Fundo,
            surface = Cor.Painel,
            surfaceVariant = Cor.Cartao,
            onSurface = Color.White,
            onBackground = Color.White,
        ),
    ) {
        CompositionLocalProvider(LocalTv provides tv, LocalBringIntoViewSpec provides RolagemMinima, content = content)
    }
}

/**
 * Na TV, o Compose rola as listas até o item focado ficar a 30% da tela; isso esconde o topo do destaque e dos
 * detalhes. Aqui a lista só rola o necessário para o item aparecer inteiro (como no celular).
 */
@OptIn(ExperimentalFoundationApi::class)
private object RolagemMinima : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val fim = offset + size
        return when {
            offset >= 0 && fim <= containerSize -> 0f
            offset < 0 && fim > containerSize -> 0f
            abs(offset) < abs(fim - containerSize) -> offset
            else -> fim - containerSize
        }
    }
}

/** Controle remoto / teclado (na TV, ou um teclado no celular): aí o foco aparece e é movido pelo app. */
@Composable
fun usandoControle(): Boolean = LocalTv.current || LocalInputModeManager.current.inputMode == InputMode.Keyboard

class Medidas(
    val largo: Boolean,
    val tv: Boolean,
    val margem: Dp,
    val cartao: Dp,
    val canal: Dp,
    val larguraTela: Dp,
    val alturaTela: Dp,
)

@Composable
fun medidas(): Medidas {
    val c = LocalConfiguration.current
    val tv = LocalTv.current
    val w = c.screenWidthDp
    val largo = w >= 600
    return Medidas(
        largo = largo,
        tv = tv,
        margem = if (tv) 48.dp else if (largo) 32.dp else 16.dp,
        cartao = if (tv) 120.dp else if (largo) 128.dp else 108.dp,
        canal = if (tv) 150.dp else if (largo) 170.dp else 150.dp,
        larguraTela = w.dp,
        alturaTela = c.screenHeightDp.dp,
    )
}

/** Ícones que não existem no pacote básico do Material (desenhados aqui para não pesar o APK). */
object Icones {
    private fun icone(nome: String, vararg caminhos: String, contorno: Boolean = false): ImageVector {
        val b = ImageVector.Builder(nome, 24.dp, 24.dp, 24f, 24f)
        for (d in caminhos) {
            if (contorno) b.addPath(addPathNodes(d), stroke = SolidColor(Color.White), strokeLineWidth = 2f)
            else b.addPath(addPathNodes(d), fill = SolidColor(Color.White))
        }
        return b.build()
    }

    val Pausa = icone("pausa", "M6,5h4v14H6z", "M14,5h4v14h-4z")
    val Proximo = icone("proximo", "M6,6L14.5,12L6,18z", "M16,6h2v12h-2z")
    val Voltar10 = icone("voltar10", "M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6 -6,-2.69 -6,-6H4c0,4.42 3.58,8 8,8s8,-3.58 8,-8 -3.58,-8 -8,-8z")
    val Avancar10 = icone("avancar10", "M12,5V1l5,5 -5,5V7c-3.31,0 -6,2.69 -6,6s2.69,6 6,6 6,-2.69 6,-6h2c0,4.42 -3.58,8 -8,8s-8,-3.58 -8,-8 3.58,-8 8,-8z")
    val Legendas = icone(
        "legendas",
        "M20,4H4C2.9,4 2,4.9 2,6v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6C22,4.9 21.1,4 20,4zM4,12h4v2H4V12zM14,18H4v-2h10V18zM20,18h-4v-2h4V18zM20,14H10v-2h10V14z",
    )
    val Velocidade = icone("velocidade", "M3.5,15a8.5,8.5 0,1 1,17 0", "M12,15L16.5,9.5", contorno = true)
    val Tv = icone("tv", "M21,3H3C1.9,3 1,3.9 1,5v12c0,1.1 0.9,2 2,2h5v2h8v-2h5c1.1,0 1.99,-0.9 1.99,-2L23,5C23,3.9 22.1,3 21,3zM21,17H3V5h18V17z")
    val Episodios = icone("episodios", "M3,5h18v2H3z", "M3,11h18v2H3z", "M3,17h18v2H3z")
    val Acima = icone("acima", "M7,15l5,-5 5,5z")
    val Abaixo = icone("abaixo", "M7,10l5,5 5,-5z")
}
