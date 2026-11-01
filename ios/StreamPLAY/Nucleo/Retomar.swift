import Foundation

final class PlayRequest: Identifiable {
    let item: Item
    let episode: Episode?
    let channels: [Item]?
    var fromStart: Bool

    init(_ item: Item, episode: Episode? = nil, channels: [Item]? = nil, fromStart: Bool = false) {
        self.item = item
        self.episode = episode
        self.channels = channels
        self.fromStart = fromStart
    }

    var id: ObjectIdentifier { ObjectIdentifier(self) }
    var isLive: Bool { item.kind == .channel }
    var url: String? { episode?.url ?? item.url }
    var options: [String] { episode?.options ?? item.options }
    var key: String { episode?.key ?? item.key }
    var showKey: String? { episode?.show.key }
    var title: String { episode?.show.name ?? item.name }

    @MainActor
    static func forItem(_ i: Item?) -> PlayRequest? {
        guard let i = i else { return nil }
        switch i.kind {
        case .series: return forEpisode(Resume.episodeFor(i))
        case .channel: return forChannel(i, nil)
        case .movie: return PlayRequest(i)
        }
    }

    static func forEpisode(_ e: Episode?) -> PlayRequest? { e.map { PlayRequest($0.show, episode: $0) } }

    static func forChannel(_ c: Item, _ list: [Item]?) -> PlayRequest {
        PlayRequest(c, channels: (list?.isEmpty == false) ? list : [c])
    }
}

/// Regras de "continuar assistindo" e próximo episódio.
@MainActor
enum Resume {
    static func episodeFor(_ show: Item?) -> Episode? {
        guard let show = show, !show.episodes.isEmpty else { return nil }
        var best: Episode? = nil
        var bp: Progresso? = nil
        for e in show.episodes {
            if let p = Store.s.getProgress(e.key), bp == nil || p.at > bp!.at { best = e; bp = p }
        }
        guard let b = best, let p = bp else { return show.episodes[0] }
        if p.finished { return next(b) ?? show.episodes[0] }
        return b
    }

    static func next(_ e: Episode?) -> Episode? {
        guard let e = e, let i = e.show.episodes.firstIndex(of: e) else { return nil }
        let list = e.show.episodes
        return i + 1 < list.count ? list[i + 1] : nil
    }

    /// kind = nil: filmes e séries juntos (aba Início).
    static func continueCards(_ lib: Library, _ kind: Kind?) -> [Cartao] {
        var cards: [Cartao] = []
        var seen = Set<String>()
        for (key, p) in Store.s.progress.sorted(by: { $0.value.at > $1.value.at }) {
            if kind != .series && key.hasPrefix("mv:") {
                guard let it = lib.get(key), !p.finished, p.pos >= 20000 else { continue }
                cards.append(Cartao(item: it, progress: p.fraction, sub: faltam(p.dur - p.pos), direct: true))
            } else if kind != .movie && key.hasPrefix("ep:") {
                guard let ep = lib.episode(key), seen.insert(ep.show.key).inserted else { continue }
                var target = ep
                var frac = p.fraction
                if p.finished {
                    guard let n = next(ep) else { continue }
                    target = n
                    frac = Store.s.watched(n.key)
                } else if p.pos < 20000 { continue }
                cards.append(Cartao(item: ep.show, ep: target, progress: max(frac, 0.006), sub: "T\(target.season) • E\(target.number)"))
            }
            if cards.count >= 20 { break }
        }
        return cards
    }
}
