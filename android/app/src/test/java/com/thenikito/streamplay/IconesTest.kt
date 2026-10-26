package com.thenikito.streamplay

import com.thenikito.streamplay.ui.Icones
import org.junit.Assert.assertTrue
import org.junit.Test

class IconesTest {
    /** Os ícones do player são desenhados por texto (SVG): um erro de digitação só apareceria ao abrir o player. */
    @Test
    fun icones() {
        val todos = listOf(
            Icones.Pausa, Icones.Proximo, Icones.Voltar10, Icones.Avancar10, Icones.Legendas,
            Icones.Velocidade, Icones.Tv, Icones.Episodios, Icones.Acima, Icones.Abaixo,
        )
        for (i in todos) assertTrue(i.name, i.root.size > 0)
    }
}
