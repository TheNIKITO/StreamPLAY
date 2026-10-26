package com.thenikito.streamplay

import java.io.BufferedReader
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

enum class Kind { Channel, Movie, Series }

class Category(val name: String, val kind: Kind, val isRelease: Boolean, val order: Int) {
    val items = ArrayList<Item>()
}

/**
 * Filme, canal ou série. As listas podem ter centenas de milhares de itens e a TV tem pouca memória, então o id
 * fica num Long (os mesmos 8 primeiros bytes do SHA-1 que o app do PC usa) e as listas internas só existem quando
 * precisam.
 */
class Item(
    val id: Long,
    val kind: Kind,
    val name: String,
    var logo: String?,
    val url: String?,
    val options: List<String>,
    val order: Int,
) {
    var backdrop: String? = null
    var plot: String? = null
    var year: String? = null
    var genre: String? = null
    var rating: String? = null
    var isRelease = false

    /** Nome normalizado, calculado na primeira busca. */
    internal var busca: String? = null

    private var eps: ArrayList<Episode>? = null
    val episodes: List<Episode> get() = eps ?: emptyList()

    internal fun addEpisode(e: Episode) {
        (eps ?: ArrayList<Episode>().also { eps = it }).add(e)
    }

    internal fun sortEpisodes() {
        eps?.sortWith(compareBy<Episode>({ it.season }, { it.number }, { it.order }))
    }

    // quase todo item está numa categoria só: guarda a String direto e só cria a lista quando aparece outra
    private var cats: Any? = null

    @Suppress("UNCHECKED_CAST")
    val categories: List<String>
        get() = when (val c = cats) {
            null -> emptyList()
            is String -> listOf(c)
            else -> c as List<String>
        }

    /** Devolve false se o item já estava nessa categoria. */
    @Suppress("UNCHECKED_CAST")
    internal fun addCategory(name: String): Boolean {
        when (val c = cats) {
            null -> cats = name
            is String -> {
                if (c == name) return false
                cats = arrayListOf(c, name)
            }
            else -> {
                val l = c as ArrayList<String>
                if (name in l) return false
                l.add(name)
            }
        }
        return true
    }

    val key: String get() = prefixo(kind) + hex(id)
    val hasBackdrop: Boolean get() = !backdrop.isNullOrEmpty()
    val heroImage: String? get() = if (hasBackdrop) backdrop else logo
    val kindLabel: String get() = when (kind) { Kind.Movie -> "F I L M E"; Kind.Series -> "S É R I E"; Kind.Channel -> "C A N A L" }
    val category: String get() = categories.firstOrNull() ?: ""
    val seasons: List<Int> get() = episodes.map { it.season }.distinct().sorted()

    val countText: String
        get() {
            val s = seasons.size
            return if (s > 1) "$s temporadas" else if (episodes.size == 1) "1 episódio" else "${episodes.size} episódios"
        }

    val meta: String
        get() {
            val parts = ArrayList<String>()
            if (!year.isNullOrEmpty()) parts.add(year!!)
            if (kind == Kind.Series) parts.add(countText)
            if (!genre.isNullOrEmpty()) parts.add(genre!!) else if (category.isNotEmpty()) parts.add(category)
            return parts.joinToString("  •  ")
        }

    companion object {
        fun prefixo(k: Kind) = when (k) { Kind.Channel -> "ch:"; Kind.Movie -> "mv:"; Kind.Series -> "sr:" }
    }
}

class Episode(
    val id: Long,
    val season: Int,
    val number: Int,
    private val titulo: String?,
    val url: String,
    val logo: String?,
    val plot: String?,
    val options: List<String>,
    val show: Item,
    val order: Int,
) {
    val title: String get() = titulo ?: "Episódio $number"
    val key: String get() = "ep:" + hex(id)
    val label: String get() = "T$season:E$number"
    val image: String? get() = if (logo.isNullOrEmpty()) show.logo else logo
}

/** Cartão das fileiras e grades: um item + o estado de exibição. */
class Cartao(
    val item: Item,
    val ep: Episode? = null,
    val progress: Double = 0.0,
    val sub: String? = null,
    val isNew: Boolean = false,
    /** clicar já começa a tocar (Continuar assistindo) */
    val direct: Boolean = false,
) {
    val hasProgress: Boolean get() = progress > 0.005
}

fun iniciais(nome: String?): String =
    (nome ?: "").split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.substring(0, 1).uppercase() }

class Library {
    val channels = ArrayList<Category>()
    val movies = ArrayList<Category>()
    val series = ArrayList<Category>()
    val allChannels = ArrayList<Item>()
    val allMovies = ArrayList<Item>()
    val allSeries = ArrayList<Item>()
    internal val mapas = arrayOf(HashMap<Long, Item>(), HashMap<Long, Item>(), HashMap<Long, Item>())
    internal val episodios = HashMap<Long, Episode>()

    fun cats(k: Kind): ArrayList<Category> = when (k) { Kind.Channel -> channels; Kind.Movie -> movies; Kind.Series -> series }
    fun all(k: Kind): ArrayList<Item> = when (k) { Kind.Channel -> allChannels; Kind.Movie -> allMovies; Kind.Series -> allSeries }

    fun get(key: String?): Item? {
        if (key == null || key.length != 19 || key[2] != ':') return null
        val k = when (key.substring(0, 3)) { "ch:" -> Kind.Channel; "mv:" -> Kind.Movie; "sr:" -> Kind.Series; else -> return null }
        val id = parseHex(key, 3) ?: return null
        return mapas[k.ordinal][id]
    }

    fun episode(key: String?): Episode? {
        if (key == null || !key.startsWith("ep:")) return null
        return episodios[parseHex(key, 3) ?: return null]
    }

    val summary: String
        get() = "${allMovies.size} filme${if (allMovies.size == 1) "" else "s"}  •  " +
            "${allSeries.size} série${if (allSeries.size == 1) "" else "s"}  •  " +
            "${allChannels.size} cana${if (allChannels.size == 1) "l" else "is"}"
}

internal fun hex(v: Long): String {
    val c = CharArray(16)
    var x = v
    for (i in 15 downTo 0) {
        c[i] = "0123456789abcdef"[(x and 15L).toInt()]
        x = x ushr 4
    }
    return String(c)
}

internal fun parseHex(s: String, from: Int): Long? {
    if (s.length - from != 16) return null
    var v = 0L
    for (i in from until s.length) {
        val d = Character.digit(s[i], 16)
        if (d < 0) return null
        v = (v shl 4) or d.toLong()
    }
    return v
}

/** Os 8 primeiros bytes do SHA-1 (o app do PC usa o mesmo, em hexadecimal). */
internal class Hasher {
    private val md = MessageDigest.getInstance("SHA-1")
    fun hash(s: String): Long {
        val b = md.digest(s.toByteArray(Charsets.UTF_8))
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[i].toLong() and 0xff)
        return v
    }
}

/** Leitura da lista M3U — as mesmas regras do app do PC (Library.cs). */
object M3U {
    // "FILMES | Lançamentos", "SERIES - Ação", "CANAIS: Esportes", "CANAIS = Esportes", "FILMES" ...
    private val GROUP_RX = Regex(
        "^\\s*(filmes?|movies?|vod|s[eé]ries?|seriados?|canais|canal)(?![a-zà-ÿ])\\s*[|:\\-–—/>=]*\\s*(.*)$",
        RegexOption.IGNORE_CASE,
    )

    // "Nome S01E02", "Nome - S1 E2 - Título", "Nome T01E02", "Nome 1x02"
    private val EP_RX = Regex(
        "^(.*?)[\\s._\\-–|:]*(?:[ST](\\d{1,2})\\s*[._\\-]?\\s*E[Pp]?\\s*(\\d{1,4})|(\\d{1,2})x(\\d{1,4}))(?![0-9])(.*)$",
        RegexOption.IGNORE_CASE,
    )
    private val EP_RX2 = Regex(
        "^(.*?)[\\s._\\-–|:]*Temporada\\s*(\\d{1,2})[\\s._\\-–|:]*Epis[oó]dio\\s*(\\d{1,4})(.*)$",
        RegexOption.IGNORE_CASE,
    )
    private val DE_RX = Regex("^(de|da|do|das|dos)\\s+", RegexOption.IGNORE_CASE)

    private val VIDEO_EXT = arrayOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".m4v", ".webm", ".flv", ".mpg", ".mpeg", ".divx")
    private val CONECTIVOS = setOf("De", "Da", "Do", "Das", "Dos", "E", "Em", "Na", "No", "Para")
    private val PT_BR = Locale("pt", "BR")

    fun isUrl(s: String) = s.startsWith("http://", true) || s.startsWith("https://", true)

    /** Lê a lista linha a linha (sem carregar o arquivo inteiro na memória). */
    fun parse(reader: BufferedReader, baseDir: String?, progresso: ((Int) -> Unit)? = null): Library {
        val lib = Library()
        val cats = HashMap<String, Category>()          // tipo + nome normalizado
        val catRapido = HashMap<String, Category>()     // tipo + nome como veio (evita normalizar de novo)
        val grupos = HashMap<String?, Pair<Kind?, String?>>()
        val bonitos = HashMap<String, String>()
        val shows = HashMap<String, Item>()
        val h = Hasher()
        val attrs = HashMap<String, String>()
        var inf: String? = null
        var grp: String? = null
        var opts: ArrayList<String>? = null
        var order = 0
        var catOrder = 0
        var n = 0
        var primeira = true

        while (true) {
            var line = reader.readLine() ?: break
            if (primeira) {
                if (line.isNotEmpty() && line[0].code == 0xFEFF) line = line.substring(1)
                primeira = false
            }
            line = line.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", true)) { inf = line; grp = null; opts = null; continue }
            if (line.startsWith("#EXTGRP:", true)) { grp = line.substring(8).trim(); continue }
            if (line.startsWith("#EXTVLCOPT:", true)) {
                (opts ?: ArrayList<String>(2).also { opts = it }).add(line.substring(11).trim())
                continue
            }
            if (line.startsWith("#")) continue

            val url = resolve(line, baseDir)
            attrs.clear()
            val title = parseInf(inf, attrs)
            inf = null
            val name = firstNonEmpty(title, attrs["tvg-name"], nameFromUrl(url))
            val group = firstNonEmpty(attrs["group-title"], grp)
            val logo = a(attrs, "tvg-logo", "logo", "capa", "poster")?.let { resolve(it, baseDir) }
            val backdrop = a(attrs, "backdrop", "fundo", "tvg-backdrop", "banner", "background")?.let { resolve(it, baseDir) }

            val (kind0, cat0) = grupos.getOrPut(group) { classify(group) }
            val kind = kind0 ?: run {
                val path = url.substringBefore('?').lowercase(Locale.ROOT)
                val video = VIDEO_EXT.any { path.endsWith(it) }
                when {
                    path.contains("/series/") || (video && (EP_RX.containsMatchIn(name) || EP_RX2.containsMatchIn(name))) -> Kind.Series
                    path.contains("/movie/") || video -> Kind.Movie
                    else -> Kind.Channel
                }
            }
            val cru = if (cat0.isNullOrBlank()) when (kind) { Kind.Movie -> "Filmes"; Kind.Series -> "Séries"; Kind.Channel -> "Canais" } else cat0
            val cat = bonitos.getOrPut(cru) { pretty(cru) }

            val rapido = kind.ordinal.toString() + cat
            val category = catRapido[rapido] ?: run {
                val k = kind.ordinal.toString() + norm(cat)
                cats[k] ?: Category(cat, kind, isReleaseName(cat), catOrder++).also {
                    cats[k] = it
                    lib.cats(kind).add(it)
                }
            }.also { catRapido[rapido] = it }

            val plot = a(attrs, "tvg-plot", "plot", "descricao", "descrição", "description", "sinopse")
            val year = a(attrs, "year", "ano", "tvg-year")
            val genre = a(attrs, "genre", "genero", "gênero", "tvg-genre")
            val rating = a(attrs, "rating", "nota", "tvg-rating")
            val itemOpts: List<String> = opts ?: emptyList()

            if (kind == Kind.Series) {
                val sp = splitEpisode(name)
                val showKey = norm(sp.show)
                val show = shows[showKey] ?: Item(h.hash(showKey), Kind.Series, sp.show, null, null, emptyList(), order++).also {
                    shows[showKey] = it
                    lib.allSeries.add(it)
                    lib.mapas[Kind.Series.ordinal][it.id] = it
                }
                fill(show, plot, year, genre, rating, backdrop)
                if (show.logo.isNullOrEmpty()) show.logo = logo
                var number = sp.number
                if (number <= 0) number = show.episodes.count { it.season == sp.season } + 1
                val ep = Episode(
                    h.hash("$url|$name"), sp.season, number, sp.title?.takeIf { it.isNotBlank() }, url, logo, plot,
                    itemOpts, show, order++,
                )
                show.addEpisode(ep)
                lib.episodios[ep.id] = ep
                if (category.isRelease) show.isRelease = true
                addTo(category, show)
            } else {
                // mesmo link + mesmo nome = mesmo item (aparece em várias categorias); nomes diferentes ficam separados
                val id = h.hash(url + "|" + norm(name))
                val mapa = lib.mapas[kind.ordinal]
                val item = mapa[id] ?: Item(id, kind, name, logo, url, itemOpts, order++).also {
                    mapa[id] = it
                    lib.all(kind).add(it)
                }
                fill(item, plot, year, genre, rating, backdrop)
                if (item.logo.isNullOrEmpty()) item.logo = logo
                if (category.isRelease) item.isRelease = true
                addTo(category, item)
            }
            opts = null
            n++
            if (progresso != null && n % 5000 == 0) progresso(n)
        }

        for (s in lib.allSeries) s.sortEpisodes()
        for (list in arrayOf(lib.movies, lib.series, lib.channels)) {
            val sorted = list.sortedWith(compareBy<Category>({ !it.isRelease }, { it.order }))
            list.clear()
            list.addAll(sorted)
        }
        return lib
    }

    private fun addTo(c: Category, i: Item) {
        if (i.addCategory(c.name)) c.items.add(i)
    }

    private fun fill(t: Item, plot: String?, year: String?, genre: String?, rating: String?, backdrop: String?) {
        if (t.plot == null) t.plot = plot
        if (t.year == null) t.year = year
        if (t.genre == null) t.genre = genre
        if (t.rating == null) t.rating = rating
        if (t.backdrop == null) t.backdrop = backdrop
    }

    fun classify(group: String?): Pair<Kind?, String?> {
        if (group.isNullOrBlank()) return Pair(null, null)
        val m = GROUP_RX.find(group) ?: return Pair(null, group.trim())
        val w = norm(m.groupValues[1])
        val k = when {
            w.startsWith("FILME") || w.startsWith("MOVIE") || w == "VOD" -> Kind.Movie
            w.startsWith("SERIE") || w.startsWith("SERIADO") -> Kind.Series
            else -> Kind.Channel
        }
        var cat = m.groupValues[2].trim().trim('|', '-', ':', '/', '=').trim()
        cat = DE_RX.replace(cat, "")
        if (cat.isNotEmpty()) cat = cat.substring(0, 1).uppercase(PT_BR) + cat.substring(1)
        return Pair(k, cat)
    }

    class Partes(val show: String, val season: Int, val number: Int, val title: String?)

    fun splitEpisode(name: String): Partes {
        val n: String
        val s: String
        val e: String
        val r: String
        val m = EP_RX.find(name)
        if (m != null) {
            val g = m.groups
            n = g[1]?.value ?: ""
            s = g[2]?.value ?: g[4]?.value ?: "1"
            e = g[3]?.value ?: g[5]?.value ?: "0"
            r = g[6]?.value ?: ""
        } else {
            val m2 = EP_RX2.find(name) ?: return Partes(name.trim(), 1, 0, null)
            n = m2.groupValues[1]
            s = m2.groupValues[2]
            e = m2.groupValues[3]
            r = m2.groupValues[4]
        }
        if (n.isBlank()) return Partes(name.trim(), 1, 0, null)
        val show = n.trim().trimEnd('-', '–', '|', ':', '.', '_').trim()
        val rest = r.trim().trimStart('-', '–', '|', ':', '.', '_').trim()
        return Partes(show, s.toInt(), e.toInt(), rest)
    }

    /** Separa os atributos (tvg-name="..." group-title="...") do título depois da vírgula. */
    fun parseInf(line: String?, attrs: HashMap<String, String>): String {
        if (line == null) return ""
        val start = line.indexOf(':') + 1
        var comma = -1
        var q = false
        for (i in start until line.length) {
            val c = line[i]
            if (c == '"') q = !q else if (c == ',' && !q) { comma = i; break }
        }
        val end = if (comma >= 0) comma else line.length
        parseAttrs(line, start, end, attrs)
        return if (comma >= 0) line.substring(comma + 1).trim() else ""
    }

    private fun isW(c: Char) = c == '_' || c == '-' || Character.isLetterOrDigit(c)

    // equivale à regex ([\w-]+)\s*=\s*"([^"]*)" do app do PC, sem o custo da regex em listas enormes
    private fun parseAttrs(s: String, from: Int, to: Int, out: HashMap<String, String>) {
        var i = from
        while (i < to) {
            if (!isW(s[i])) { i++; continue }
            val ini = i
            while (i < to && isW(s[i])) i++
            val fimNome = i
            var j = i
            while (j < to && s[j].isWhitespace()) j++
            if (j >= to || s[j] != '=') continue
            j++
            while (j < to && s[j].isWhitespace()) j++
            if (j >= to || s[j] != '"') { i = j; continue }
            val fimValor = s.indexOf('"', j + 1)
            if (fimValor < 0 || fimValor > to) break
            out[s.substring(ini, fimNome).lowercase(Locale.ROOT)] = s.substring(j + 1, fimValor).trim()
            i = fimValor + 1
        }
    }

    private fun a(attrs: HashMap<String, String>, vararg names: String): String? {
        for (n in names) {
            val v = attrs[n]
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    private fun firstNonEmpty(vararg v: String?): String = v.firstOrNull { !it.isNullOrBlank() }?.trim() ?: "Sem nome"

    private fun nameFromUrl(url: String): String? = try {
        val p = if (isUrl(url)) URI(url).path ?: "" else url
        var n = p.substring(maxOf(p.lastIndexOf('/'), p.lastIndexOf('\\')) + 1)
        val dot = n.lastIndexOf('.')
        if (dot > 0) n = n.substring(0, dot)
        n.replace('.', ' ').replace('_', ' ').takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    fun resolve(s0: String, base: String?): String {
        val s = s0.trim().trim('"')
        if (isUrl(s) || s.contains("://") || s.startsWith("data:", true)) return s
        if (s.startsWith("/") || (s.length > 2 && s[1] == ':' && (s[2] == '\\' || s[2] == '/'))) return s
        if (base == null || !isUrl(base)) return s
        return try { URL(URL(base), s).toString() } catch (_: Exception) { s }
    }

    private fun isReleaseName(cat: String): Boolean {
        val n = norm(cat)
        return n.contains("LANCAMENTO") || n.contains("NOVIDADE") || n.contains("ESTREIA") || n.contains("RELEASE") || n == "NOVOS"
    }

    /** "LANÇAMENTOS" -> "Lançamentos"; nomes já com maiúsculas e minúsculas ficam como estão. */
    fun pretty(s0: String): String {
        val s = s0.trim()
        if (s.any { it.isLowerCase() } || s.length <= 3) return s
        val low = s.lowercase(PT_BR)
        val t = StringBuilder(low.length)
        var dentro = false
        for (c in low) {
            t.append(if (!dentro && c.isLetter()) c.titlecaseChar() else c)
            dentro = c.isLetterOrDigit() || c == '\''
        }
        // conectivos em minúsculas: "Ação e Aventura"
        val out = StringBuilder(t.length)
        var i = 0
        while (i < t.length) {
            if (!t[i].isLetterOrDigit()) { out.append(t[i]); i++; continue }
            var j = i
            while (j < t.length && t[j].isLetterOrDigit()) j++
            val w = t.substring(i, j)
            out.append(if (w in CONECTIVOS) w.lowercase(PT_BR) else w)
            i = j
        }
        return out.toString()
    }

    /** Maiúsculas, sem acentos e com espaços simples (para comparar nomes). */
    fun norm(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        var ascii = true
        for (c in s) if (c.code >= 128) { ascii = false; break }
        val d = if (ascii) s else Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder(d.length)
        var espaco = false
        for (c in d) {
            if (!ascii && Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
            if (c.isWhitespace()) { espaco = true; continue }
            if (espaco && sb.isNotEmpty()) sb.append(' ')
            espaco = false
            sb.append(c.uppercaseChar())
        }
        return sb.toString()
    }
}
