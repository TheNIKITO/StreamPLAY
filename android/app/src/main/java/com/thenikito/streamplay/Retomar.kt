package com.thenikito.streamplay

class PlayRequest(
    val item: Item,
    val episode: Episode? = null,
    val channels: List<Item>? = null,
    var fromStart: Boolean = false,
) {
    val isLive: Boolean get() = item.kind == Kind.Channel
    val url: String? get() = episode?.url ?: item.url
    val options: List<String> get() = episode?.options ?: item.options
    val key: String get() = episode?.key ?: item.key
    val showKey: String? get() = episode?.show?.key
    val title: String get() = episode?.show?.name ?: item.name

    companion object {
        fun forItem(i: Item?): PlayRequest? = when (i?.kind) {
            null -> null
            Kind.Series -> forEpisode(Resume.episodeFor(i))
            Kind.Channel -> forChannel(i, null)
            Kind.Movie -> PlayRequest(i)
        }

        fun forEpisode(e: Episode?): PlayRequest? = e?.let { PlayRequest(it.show, it) }

        fun forChannel(c: Item, list: List<Item>?) = PlayRequest(c, channels = if (!list.isNullOrEmpty()) list else listOf(c))
    }
}

/** Regras de "continuar assistindo" e próximo episódio. */
object Resume {
    fun episodeFor(show: Item?): Episode? {
        if (show == null || show.episodes.isEmpty()) return null
        var best: Episode? = null
        var bp: Progresso? = null
        for (e in show.episodes) {
            val p = Store.getProgress(e.key)
            if (p != null && (bp == null || p.at > bp.at)) { best = e; bp = p }
        }
        if (best == null || bp == null) return show.episodes[0]
        if (bp.finished) return next(best) ?: show.episodes[0]
        return best
    }

    fun next(e: Episode?): Episode? {
        val list = e?.show?.episodes ?: return null
        val i = list.indexOf(e)
        return if (i >= 0 && i + 1 < list.size) list[i + 1] else null
    }

    fun continueCards(lib: Library, kind: Kind): List<Cartao> {
        val cards = ArrayList<Cartao>()
        val seen = HashSet<String>()
        for ((key, p) in Store.progress.entries.sortedByDescending { it.value.at }) {
            if (kind == Kind.Movie && key.startsWith("mv:")) {
                val it = lib.get(key)
                if (it == null || p.finished || p.pos < 20000) continue
                cards.add(Cartao(it, progress = p.fraction, sub = faltam(p.dur - p.pos), direct = true))
            } else if (kind == Kind.Series && key.startsWith("ep:")) {
                val ep = lib.episode(key) ?: continue
                if (!seen.add(ep.show.key)) continue
                var target: Episode = ep
                var frac = p.fraction
                if (p.finished) {
                    target = next(ep) ?: continue
                    frac = Store.watched(target.key)
                } else if (p.pos < 20000) continue
                cards.add(Cartao(ep.show, ep = target, progress = maxOf(frac, 0.006), sub = "${target.label}  ${target.title}"))
            }
            if (cards.size >= 20) break
        }
        return cards
    }
}
