package com.thenikito.streamplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContaTest {
    @Test
    fun mesmoIdDoPainel() {
        // calculado com o SHA-256 do .NET (Conta.IdAcesso do app do PC); o login vai em minúsculas e sem espaços
        assertEquals(
            "9e8b8fbbd0b174ac9fa6c10f2604aa032c8bdaaab4c5cebbbe29694ede3872f7",
            Conta.idAcesso("  Joao.Silva ", "Senha@123"),
        )
    }

    private fun acesso(ativo: Boolean = true, manual: Boolean = false, bloqueia: Int? = null, liberado: Int? = null, venc: Int? = null) =
        Conta.Acesso("joao", "João", "Mensal", "TV do Zé", "pix@ze.com", ativo, manual, bloqueia, liberado, venc, 30.0)

    @Test
    fun regrasDeBloqueio() {
        val hoje = 20261025
        assertEquals(Conta.Estado.Liberado, Conta.avaliar(acesso(), hoje).estado)
        assertEquals(Conta.Estado.Bloqueado, Conta.avaliar(acesso(ativo = false), hoje).estado)
        assertEquals("Esta conta está desativada. Fale com TV do Zé.", Conta.avaliar(acesso(ativo = false), hoje).mensagem)
        assertEquals(Conta.Estado.Bloqueado, Conta.avaliar(acesso(manual = true), hoje).estado)
        // bloqueia no dia certo, mesmo que o painel não seja aberto
        assertEquals(Conta.Estado.Liberado, Conta.avaliar(acesso(bloqueia = 20261026), hoje).estado)
        val b = Conta.avaliar(acesso(bloqueia = 20261025, venc = 20261020), hoje)
        assertEquals(Conta.Estado.Bloqueado, b.estado)
        assertTrue(b.mensagem, b.mensagem.startsWith("Sua fatura de R\$") && b.mensagem.contains("venceu em 20/10/2026"))
        // "liberar até" segura o bloqueio
        assertEquals(Conta.Estado.Liberado, Conta.avaliar(acesso(bloqueia = 20261020, liberado = 20261025), hoje).estado)
        assertEquals(Conta.Estado.Bloqueado, Conta.avaliar(acesso(bloqueia = 20261020, liberado = 20261024), hoje).estado)
    }

    @Test
    fun lembreteDeVencimento() {
        val hoje = 20261030
        fun aviso(venc: Int) = Conta.aviso(Conta.avaliar(acesso(venc = venc), hoje))
        assertNull(aviso(20261110))
        assertTrue(aviso(20261102)!!.contains("vence em 3 dias (02/11)"))
        assertTrue(aviso(20261031)!!.contains("vence em 1 dia (31/10)"))
        assertTrue(aviso(20261030)!!.endsWith("vence hoje."))
        assertTrue(aviso(20261028)!!.contains("venceu em 28/10"))
    }

    @Test
    fun leDocumentoDoFirestore() {
        val json = """
            {"name":"x","fields":{
              "login":{"stringValue":"joao"},"negocio":{"stringValue":"TV do Zé"},"pix":{"stringValue":"pix@ze.com"},
              "ativo":{"booleanValue":true},"manual":{"booleanValue":false},
              "bloqueiaEm":{"stringValue":"2026-11-13"},"liberadoAte":{"nullValue":null},
              "fatura":{"mapValue":{"fields":{"venc":{"stringValue":"2026-11-10"},"valor":{"integerValue":"35"}}}}
            }}
        """.trimIndent()
        val a = Conta.ler(json)
        assertEquals("joao", a.login)
        assertTrue(a.ativo)
        assertEquals(20261113, a.bloqueiaEm)
        assertNull(a.liberadoAte)
        assertEquals(20261110, a.faturaVenc)
        assertEquals(35.0, a.faturaValor!!, 0.001)
    }

    @Test
    fun datas() {
        assertEquals(1, Dia.dias(20261231, 20270101))
        assertEquals(-3, Dia.dias(20260301, 20260226))
        assertEquals("05/03/2026", Dia.fmt(20260305))
        assertNull(Dia.parse("2026-13-01"))
        assertEquals("1:02:03", tempo(3_723_000))
        assertEquals("4:05", tempo(245_000))
        assertEquals("Faltam 1h 20min", faltam(80 * 60_000L))
        assertTrue(Atualizador.maisNova("1.3.0", "1.2.0"))
        assertTrue(!Atualizador.maisNova("1.2.0", "1.2.0"))
        assertTrue(Atualizador.maisNova("1.10.0", "1.9.9"))
    }

    @Test
    fun nomesDasFaixas() {
        assertEquals("Inglês", nomeFaixa("Track 1 - [English]", 1, false))
        assertEquals("Português (Brasil)", nomeFaixa("[pt-BR]", 1, false))
        assertEquals("Legenda 2", nomeFaixa("Track 2", 2, true))
        assertEquals("Dolby 5.1", nomeFaixa("Dolby 5.1", 1, false))
    }
}
