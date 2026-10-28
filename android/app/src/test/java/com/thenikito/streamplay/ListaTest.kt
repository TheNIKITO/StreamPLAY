package com.thenikito.streamplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class ListaTest {
    private fun ler(texto: String) = M3U.parse(BufferedReader(StringReader(texto)), "http://servidor.com/listas/lista.m3u")

    private val exemplo = """
        |#EXTM3U
        |#EXTINF:-1 tvg-name="Sintel" tvg-logo="capas/sintel.jpg" group-title="FILMES | Lançamentos" ano="2010" genero="Fantasia" descricao="Uma jovem, guerreira",Sintel
        |http://v.com/sintel.mp4
        |#EXTINF:-1 tvg-logo="http://img/bbb.jpg" group-title="FILMES",Big Buck Bunny
        |#EXTVLCOPT:http-user-agent=Mozilla
        |http://v.com/bbb.mp4
        |#EXTINF:-1 group-title="FILMES | AÇÃO E AVENTURA",Big Buck Bunny
        |http://v.com/bbb.mp4
        |#EXTINF:-1 group-title="SERIES | Drama" tvg-logo="http://img/m.jpg",Mistérios do Asfalto S01E02 - O Retorno
        |http://v.com/m102.mp4
        |#EXTINF:-1 group-title="SERIES | Drama",Mistérios do Asfalto S01E01
        |http://v.com/m101.mp4
        |#EXTINF:-1 group-title="SERIES | Drama",Mistérios do Asfalto 2x01
        |http://v.com/m201.mp4
        |#EXTINF:-1 group-title="CANAIS | Esportes" tvg-logo="http://img/esp.png",Esporte Mais
        |http://tv.com/esporte.m3u8
        |#EXTINF:-1 group-title="Notícias",Notícias 24h
        |http://tv.com/noticias.ts
        |#EXTINF:-1,Sem Grupo
        |http://tv.com/live/123
    """.trimMargin()

    @Test
    fun episodioComNomeDescricaoEImagemProprios() {
        val lib = ler(
            """
            |#EXTM3U
            |#EXTINF:-1 ep-titulo="O Plantão" ep-descricao="Adam enfrenta o primeiro plantão." ep-imagem="https://image.tmdb.org/t/p/w300/a.jpg" tvg-logo="http://img/s.jpg" group-title="SERIES | Drama" descricao="A série inteira",Hospital S01E01
            |http://v.com/h101.mp4
            |#EXTINF:-1 tvg-logo="http://img/s.jpg" group-title="SERIES | Drama",Hospital S01E02 - Do Nome
            |http://v.com/h102.mp4
            """.trimMargin(),
        )
        val show = lib.allSeries.single()
        val (e1, e2) = show.episodes
        assertEquals("O Plantão", e1.title)
        assertEquals("Adam enfrenta o primeiro plantão.", e1.plot)
        assertEquals("https://image.tmdb.org/t/p/w300/a.jpg", e1.image)
        assertEquals("A série inteira", show.plot)        // a descrição da série continua a da série
        assertEquals("Do Nome", e2.title)                   // sem ep-titulo: o título que vem no nome
        assertEquals("http://img/s.jpg", e2.image)          // sem ep-imagem: a capa da série
    }

    @Test
    fun separaFilmesSeriesECanais() {
        val lib = ler(exemplo)
        assertEquals(2, lib.allMovies.size)
        assertEquals(1, lib.allSeries.size)
        assertEquals(3, lib.allChannels.size)
        // categoria de lançamentos vem primeiro
        assertEquals(listOf("Lançamentos", "Filmes", "Ação e Aventura"), lib.movies.map { it.name })
        assertTrue(lib.movies[0].isRelease)
        assertEquals("2 filmes  •  1 série  •  3 canais", lib.summary)
    }

    @Test
    fun atributosDoFilme() {
        val lib = ler(exemplo)
        val sintel = lib.allMovies.first { it.name == "Sintel" }
        assertEquals("http://servidor.com/listas/capas/sintel.jpg", sintel.logo)
        assertEquals("2010", sintel.year)
        assertEquals("Fantasia", sintel.genre)
        assertEquals("Uma jovem, guerreira", sintel.plot)
        assertTrue(sintel.isRelease)
        assertEquals("2010  •  Fantasia", sintel.meta)
    }

    @Test
    fun mesmoLinkEMesmoNomeEhOMesmoItemEmVariasCategorias() {
        val lib = ler(exemplo)
        val bbb = lib.allMovies.first { it.name == "Big Buck Bunny" }
        assertEquals(listOf("Filmes", "Ação e Aventura"), bbb.categories)
        assertEquals(listOf("http-user-agent=Mozilla"), bbb.options)
        assertEquals(bbb, lib.get(bbb.key))
    }

    @Test
    fun episodiosAgrupadosPorSerieETemporada() {
        val lib = ler(exemplo)
        val s = lib.allSeries.single()
        assertEquals("Mistérios do Asfalto", s.name)
        assertEquals(listOf("T1:E1", "T1:E2", "T2:E1"), s.episodes.map { it.label })
        assertEquals("O Retorno", s.episodes[1].title)
        assertEquals("Episódio 1", s.episodes[0].title)
        assertEquals("http://img/m.jpg", s.logo)
        assertEquals("2 temporadas", s.countText)
        assertEquals(s.episodes[2], lib.episode(s.episodes[2].key))
    }

    @Test
    fun canaisESemGrupo() {
        val lib = ler(exemplo)
        assertEquals(listOf("Esportes", "Notícias", "Sem nome"), lib.channels.map { it.name })
    }

    @Test
    fun classificaGrupos() {
        assertEquals(Pair(Kind.Movie, "Lançamentos"), M3U.classify("FILMES | Lançamentos"))
        assertEquals(Pair(Kind.Series, "Ação"), M3U.classify("SÉRIES - Ação"))
        assertEquals(Pair(Kind.Channel, "Esportes"), M3U.classify("CANAIS: Esportes"))
        assertEquals(Pair(Kind.Channel, "Esportes"), M3U.classify("CANAIS = Esportes"))
        assertEquals(Pair(Kind.Channel, ""), M3U.classify("CANAIS ="))
        assertEquals(Pair(Kind.Movie, "Terror"), M3U.classify("Filmes de Terror"))
        assertEquals(Pair(Kind.Movie, ""), M3U.classify("VOD"))
        assertEquals(Pair(null, "Filmografia"), M3U.classify("Filmografia"))
    }

    @Test
    fun separaNomesDeEpisodio() {
        val a = M3U.splitEpisode("Breaking Bad S05E14 - Ozymandias")
        assertEquals("Breaking Bad", a.show); assertEquals(5, a.season); assertEquals(14, a.number); assertEquals("Ozymandias", a.title)
        val b = M3U.splitEpisode("Chaves 1x02")
        assertEquals("Chaves", b.show); assertEquals(1, b.season); assertEquals(2, b.number)
        val c = M3U.splitEpisode("Dark T02 E03")
        assertEquals("Dark", c.show); assertEquals(2, c.season); assertEquals(3, c.number)
        val d = M3U.splitEpisode("Novela Temporada 1 Episódio 10")
        assertEquals("Novela", d.show); assertEquals(1, d.season); assertEquals(10, d.number)
        val e = M3U.splitEpisode("Documentário Especial")
        assertEquals("Documentário Especial", e.show); assertEquals(0, e.number)
    }

    @Test
    fun nomesBonitos() {
        assertEquals("Ação e Aventura", M3U.pretty("AÇÃO E AVENTURA"))
        assertEquals("Lançamentos", M3U.pretty("LANÇAMENTOS"))
        assertEquals("Já Está Assim", M3U.pretty("Já Está Assim"))
        assertEquals("TV", M3U.pretty("TV"))
        assertEquals("ACAO E AVENTURA", M3U.norm("  Ação   e aventura "))
    }

    @Test
    fun mesmoIdDoAppDoPc() {
        // valor calculado com o SHA-1 do .NET, igual ao M3U.Hash do app do Windows
        assertEquals("56fe0725da1ed8d2", hex(Hasher().hash("http://x/a.mp4|AÇÃO É TESTE")))
        assertEquals(0x56fe0725da1ed8d2L, parseHex("mv:56fe0725da1ed8d2", 3))
        assertNull(parseHex("mv:xyz", 3))
    }

    @Test
    fun listaGrandeNaoTravaNemEstouraAMemoria() {
        val sb = StringBuilder("#EXTM3U\n")
        for (i in 0 until 316_000) {
            when (i % 4) {
                0 -> sb.append("#EXTINF:-1 tvg-logo=\"http://img.com/f$i.jpg\" group-title=\"FILMES | Gênero ${i % 40}\",Filme número $i\nhttp://v.com/movie/$i.mp4\n")
                1 -> sb.append("#EXTINF:-1 tvg-logo=\"http://img.com/s$i.jpg\" group-title=\"SERIES | Drama ${i % 30}\",Série ${i / 40} S0${i % 3 + 1}E${i % 20 + 1}\nhttp://v.com/series/$i.mp4\n")
                else -> sb.append("#EXTINF:-1 tvg-logo=\"http://img.com/c$i.png\" group-title=\"CANAIS | Grupo ${i % 50}\",Canal $i HD\nhttp://tv.com/live/$i.ts\n")
            }
        }
        val texto = sb.toString()
        val rt = Runtime.getRuntime()
        System.gc()
        val antes = rt.totalMemory() - rt.freeMemory()
        val t0 = System.nanoTime()
        val lib = ler(texto)
        val ms = (System.nanoTime() - t0) / 1_000_000
        System.gc()
        val mb = (rt.totalMemory() - rt.freeMemory() - antes) / 1_048_576
        println("316 mil itens lidos em $ms ms, ocupando uns $mb MB: ${lib.summary}")
        assertEquals(79_000, lib.allMovies.size)
        assertEquals(158_000, lib.allChannels.size)
        assertTrue(lib.allSeries.isNotEmpty())
        assertNotNull(lib.get(lib.allMovies[123].key))
    }
}
