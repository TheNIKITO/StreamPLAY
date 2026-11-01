import Foundation
import UIKit
import MobileVLCKit

struct Erro { let titulo: String; let texto: String }
struct Osd: Equatable { let texto: String; let n: Int }
struct Flash: Equatable { let tipo: String; let n: Int }

/// O motor do VLC, com as mesmas opções do Android (o primeiro player o cria; os outros reaproveitam).
enum Vlc {
    static let opcoes: [String] = [
        "--no-video-title-show", "--no-stats", "--no-snapshot-preview", "--no-avcodec-corrupted",
        "--freetype-rel-fontsize=16", "--freetype-outline-thickness=4",
        // Chromecast: se precisar converter, o modo que menos gasta processador (e bateria); sem o aviso de desempenho
        "--sout-chromecast-conversion-quality=3", "--sout-chromecast-show-perf-warning=0",
    ]
    static var versao: String? { VLCLibrary.shared().version.split(separator: " ").first.map(String.init) }
}

/// O player (VLC) com as mesmas regras do Android e do PC: retomar, próximo episódio, troca de canal e a vigia.
@MainActor
final class PlayerControle: ObservableObject {
    @Published private(set) var req: PlayRequest
    @Published private(set) var tocando = false
    @Published private(set) var ocupado = true
    @Published private(set) var tempo: Int64 = 0
    @Published private(set) var duracao: Int64 = 0
    @Published private(set) var erro: Erro?
    @Published private(set) var proximo: Episode?
    @Published private(set) var cartaoProximo = false
    @Published private(set) var fracProximo: Double = 0
    @Published private(set) var retomar: String?
    @Published private(set) var osd: Osd?
    @Published private(set) var flash: Flash?
    @Published private(set) var controles = true
    @Published private(set) var painel: String?
    @Published private(set) var pausadoInfo = false
    @Published private(set) var aspecto = "original"
    @Published private(set) var velocidade: Float = 1
    @Published private(set) var faixas = 0
    @Published private(set) var arrastando = false
    @Published private(set) var posArrasto: Double = 0
    /// Nome do Chromecast para onde o vídeo está indo (nil = tocando no próprio aparelho).
    @Published private(set) var transmitindoPara: String?
    private var cast: VLCRendererItem?
    var transmitindo: Bool { cast != nil }

    private var mp: VLCMediaPlayer?
    private weak var tela: UIView?
    private var iniciado = false
    private var fechado = false
    private var recomecarAoVoltar = false
    private var laco: Timer?
    private var nOsd = 0
    private var nFlash = 0

    private var lastTime: Int64 = -1
    private var andou = false
    private var retries = 0
    private var startedAt: Int64 = 0
    private var playingAt: Int64 = 0
    private var vigiaEspera: Int64 = 0
    private var vigiado = false
    private var semAudioDesde: Int64 = 0   // guarda do som: desde quando a faixa de áudio está desligada
    private var religadoEm: Int64 = 0
    private var religados = 0
    private var reabrirEm: Int64 = -1
    private var semAceleracaoAgora = false
    private var creditsDismissed = false
    private var ultimoToque = relogio()
    private var lastSave = relogio()
    private var pausadoEm: Int64 = 0
    private var retomarEm: Int64 = 0
    private var proxEm: Int64 = 0
    private var estadoAntes: VLCMediaPlayerState = .stopped

    /// Links em que o vídeo não apareceu com aceleração: abrem direto sem ela.
    private static var semAceleracao = Set<String>()
    private static let pt = NSRegularExpression(#"portugu|\bpt\b|\bpor\b|dublad"#)

    init(_ primeiro: PlayRequest) { req = primeiro }

    var ehAoVivo: Bool { req.isLive }
    var variosCanais: Bool { req.isLive && (req.channels?.count ?? 0) > 1 }
    var temEpisodios: Bool { req.episode != nil && req.item.episodes.count > 1 }

    // ---------- Início ----------

    func anexar(_ v: UIView) {
        tela = v
        if mp == nil {
            let p = VLCMediaPlayer(options: Vlc.opcoes)
            mp = p
            // estava transmitindo para a TV: o próximo título também vai para lá (até a pessoa escolher "Este celular")
            if let a = Transmissao.s.atual, p.setRendererItem(a) { ligarTransmissao(a) }
        }
        mp?.drawable = v
        if !iniciado && !fechado {
            iniciado = true
            start(req)
            laco = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.tick() }
            }
        }
    }

    private func start(_ r: PlayRequest) {
        guard let mp = mp else { return }
        req = r
        creditsDismissed = false
        cartaoProximo = false
        erro = nil
        lastTime = -1
        andou = false
        startedAt = relogio()
        playingAt = 0
        vigiaEspera = 0
        vigiado = false
        semAudioDesde = 0
        religadoEm = 0
        religados = 0
        retomar = nil
        pausadoInfo = false
        painel = nil
        proximo = r.episode != nil ? Resume.next(r.episode) : nil
        tempo = 0
        duracao = 0
        ocupado = true

        mp.media = criarMedia(r)
        mp.play()
        if velocidade != 1 { mp.rate = velocidade }
        aplicarAspecto()
        if r.isLive { Store.s.addRecent(r.item.key) }
        mostrarControles()
    }

    private func criarMedia(_ r: PlayRequest) -> VLCMedia {
        let url = (r.url ?? "").trimmingCharacters(in: .whitespaces)
        let media: VLCMedia
        if url.contains("://"), let u = URL(string: url) ?? URL(string: url.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? url) {
            media = VLCMedia(url: u)
        } else {
            media = VLCMedia(path: url)
        }
        // aceleração de vídeo do aparelho, voltando para o processador se o vídeo não aparecer
        if PlayerControle.semAceleracao.contains(url) || semAceleracaoAgora {
            media.addOption(":codec=avcodec,all")
            media.addOption(":avcodec-hw=none")
        }
        semAceleracaoAgora = false
        media.addOption(":network-caching=\(Store.s.caching)")
        if r.isLive {
            media.addOption(":http-reconnect")
            media.addOption(":live-caching=\(Store.s.caching)")
        }
        for o in r.options where !o.trimmingCharacters(in: .whitespaces).isEmpty {
            var t = o.trimmingCharacters(in: .whitespaces)
            while t.hasPrefix(":") { t.removeFirst() }
            media.addOption(":" + t)
        }
        if reabrirEm >= 0 {
            if !r.isLive { media.addOption(":start-time=" + segundos(reabrirEm)) }
            reabrirEm = -1
        } else if !r.isLive && !r.fromStart, let p = Store.s.getProgress(r.key), !p.finished, p.pos > 15000 {
            let at = max(0, p.pos - 3000)
            media.addOption(":start-time=" + segundos(at))
            retomar = "Continuando de \(StreamPLAY.tempo(at))"
            retomarEm = relogio()
        }
        r.fromStart = false
        return media
    }

    private func segundos(_ ms: Int64) -> String { String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), Double(ms) / 1000) }

    // ---------- Eventos do VLC (vistos no laço) ----------

    private func mudouEstado(_ st: VLCMediaPlayerState) {
        switch st {
        case .playing:
            if playingAt == 0 { playingAt = relogio() }
            pausadoInfo = false
        case .paused:
            pausadoEm = relogio()
        case .ended:
            onEnded()
        case .error:
            onError()
        case .esAdded:
            faixas += 1
        default:
            break
        }
    }

    private func onEnded() {
        guard let mp = mp else { return }
        if req.isLive {
            if retries < 3 { retries += 1; retry(1.2); return }
            mostrarErro("A transmissão foi interrompida", "O canal parou de enviar vídeo. Tente de novo em alguns instantes.")
            return
        }
        let len = Int64(mp.media?.length.intValue ?? 0)
        if len > 0 { Store.s.setProgress(req.key, len, len, req.showKey) }
        if proximo != nil && Store.s.autoNext && !creditsDismissed { playNext(); return }
        fechar()
    }

    private func onError() {
        if req.isLive && retries < 2 { retries += 1; retry(1.5); return }
        if transmitindo {
            mostrarErro("Não foi possível transmitir para \(transmitindoPara ?? "a TV")",
                        "Confira se a TV está ligada e na mesma rede Wi-Fi deste celular, e tente de novo.")
            return
        }
        let url = req.url ?? ""
        let host = url.contains("://") ? (URL(string: url)?.host ?? "") : (url as NSString).lastPathComponent
        mostrarErro("Não foi possível reproduzir",
                    "O VLC não conseguiu abrir este vídeo. Confira se o link da sua lista está funcionando" + (host.isEmpty ? "." : " (\(host))."))
    }

    private func retry(_ espera: Double) {
        ocupado = true
        let r = req
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(espera * 1_000_000_000))
            guard let self = self, !self.fechado, self.mp != nil, self.req === r else { return }
            self.start(r)
        }
    }

    private func mostrarErro(_ titulo: String, _ texto: String) {
        erro = Erro(titulo: titulo, texto: texto)
        ocupado = false
        controles = true
    }

    func tentarDeNovo() {
        retries = 0
        if mp == nil { erro = nil; return }
        start(req)
    }

    // ---------- Laço (4 vezes por segundo) ----------

    func tick() {
        guard let mp = mp, !fechado else { return }
        let now = relogio()
        let st = mp.state
        if st != estadoAntes {
            estadoAntes = st
            mudouEstado(st)
            if fechado || erro != nil { return }
        }
        let len = Int64(mp.media?.length.intValue ?? 0)
        let t = Int64(mp.time.intValue)
        let playing = mp.isPlaying && st != .paused && st != .ended && st != .error && st != .stopped

        if playing { vigiar(now, t); guardarSom(now, t) }
        if playing && t > 0 && lastTime >= 0 && t != lastTime { andou = true }
        if playing && playingAt == 0 && andou { playingAt = now }
        lastTime = t
        if playing && now - startedAt > 20_000 { retries = 0 }
        tocando = mp.isPlaying || st == .opening || st == .buffering
        // carregando: abrindo, ou tocando sem o tempo andar ainda
        ocupado = erro == nil && (st == .opening || (!andou && st != .paused && st != .ended && st != .error && st != .stopped)
            || (st == .buffering && !mp.isPlaying))

        if !req.isLive {
            if len > 0 { duracao = len; tempo = t }
            if len > 0 && playing && now - lastSave > 5000 { salvarProgresso() }
            if proximo != nil && !cartaoProximo && !creditsDismissed && len > 180_000 && t > 0 && len - t < 25_000 {
                cartaoProximo = true
                proxEm = now
            }
            if cartaoProximo && Store.s.autoNext {
                let f = Double(now - proxEm) / 10_000
                fracProximo = min(1, f)
                if f >= 1 { playNext(); return }
            }
        }

        if retomar != nil && now - retomarEm > 7000 { retomar = nil }
        // transmitindo, os controles ficam à vista (a tela do celular vira o controle remoto da TV)
        if controles && playing && painel == nil && !arrastando && erro == nil && !transmitindo && now - ultimoToque > 3200 { esconderControles() }
        if st == .paused && !pausadoInfo && painel == nil && erro == nil && !transmitindo && now - pausadoEm > 6000 && now - ultimoToque > 6000 && !req.isLive {
            pausadoInfo = true
            esconderControles()
        }
    }

    // ---------- Guarda do som (o vídeo inteiro) ----------
    // Às vezes o VLC desliga a faixa de áudio sozinho. Desligada por 3 s seguidos (6 s em .m3u8), religa; no máximo
    // a cada 15 s e 5 vezes por vídeo.
    private func guardarSom(_ now: Int64, _ t: Int64) {
        guard let mp = mp, playingAt != 0, !transmitindo, religados < 5 else { return }
        let s = Double(now - playingAt) / 1000
        let desligado = (t > 1500 || s > 12) && mp.currentAudioTrackIndex == -1 && mp.numberOfAudioTracks > 0
        if !desligado { semAudioDesde = 0; return }
        if semAudioDesde == 0 { semAudioDesde = now }
        let adaptativo = (req.url ?? "").split(separator: "?").first.map { $0.lowercased().hasSuffix(".m3u8") } ?? false
        if now - semAudioDesde < (adaptativo ? 6000 : 3000) || (religadoEm != 0 && now - religadoEm < 15_000) { return }
        guard let a = melhorAudio() else { return }
        mp.currentAudioTrackIndex = a
        religados += 1
        religadoEm = now
        semAudioDesde = 0
    }

    // ---------- Vigia dos primeiros segundos ----------
    // Alguns arquivos abrem com o vídeo sem aparecer (tela preta com som) ou sem a faixa de vídeo escolhida.
    // Se o vídeo não aparecer, reabre no mesmo ponto sem aceleração de vídeo — e lembra disso para esse link.
    private func vigiar(_ now: Int64, _ t: Int64) {
        guard let mp = mp, !vigiado, playingAt != 0, !transmitindo else { return }
        let s = Double(now - playingAt) / 1000
        if s < 1.5 || now < vigiaEspera { return }
        if t > 8000 || s > 40 { vigiado = true }
        let andando = t > 1500 || s > 12
        guard mp.numberOfVideoTracks > 0 else { return }
        if mp.currentVideoTrackIndex == -1 {
            if andando, let v = (mp.videoTrackIndexes as? [NSNumber])?.map({ $0.int32Value }).first(where: { $0 != -1 }) {
                mp.currentVideoTrackIndex = v
                vigiaEspera = now + 3000
                vigiado = false
            }
        } else if !mp.hasVideoOut && s > 3 && t > 1500 {
            let url = (req.url ?? "").trimmingCharacters(in: .whitespaces)
            if PlayerControle.semAceleracao.insert(url).inserted {
                // tem vídeo e o tempo está andando, mas nada aparece na tela
                vigiado = true
                reabrirEm = max(0, t - 1000)
                semAceleracaoAgora = true
                start(req)
            }
        }
    }

    private func melhorAudio() -> Int32? {
        let l = faixasAudio()
        if l.isEmpty { return nil }
        return (l.first { PlayerControle.pt.acha($0.1) } ?? l[0]).0
    }

    func salvarProgresso() {
        guard let mp = mp, !req.isLive else { return }
        let len = Int64(mp.media?.length.intValue ?? 0)
        let t = Int64(mp.time.intValue)
        lastSave = relogio()
        if len <= 0 || t < 0 { return }
        if t < 10_000 && Store.s.getProgress(req.key) == nil { return }
        Store.s.setProgress(req.key, t, len, req.showKey)
    }

    // ---------- Controles ----------

    func poke() {
        ultimoToque = relogio()
        if pausadoInfo { pausadoInfo = false }
        if !controles { mostrarControles() }
    }

    func mostrarControles() {
        controles = true
        ultimoToque = relogio()
    }

    func esconderControles() {
        controles = false
        painel = nil
    }

    func alternarControles() {
        if painel != nil { painel = nil; return }
        if controles { esconderControles() } else { poke() }
    }

    func abrirPainel(_ nome: String) {
        painel = painel == nome ? nil : nome
        controles = true
        ultimoToque = relogio()
    }

    func fecharPainel() {
        painel = nil
        ultimoToque = relogio()
    }

    // ---------- Reprodução ----------

    func togglePlay() {
        guard let mp = mp else { return }
        let st = mp.state
        if st == .stopped || st == .ended || st == .error { start(req); return }
        nFlash += 1
        if mp.isPlaying {
            mp.pause()
            pausadoEm = relogio()
            salvarProgresso()
            Estado.s.enviarSincronia()   // pausou: o outro aparelho já pode continuar daqui
            tocando = false
            flash = Flash(tipo: "pause", n: nFlash)
        } else {
            mp.play()
            tocando = true
            flash = Flash(tipo: "play", n: nFlash)
            pausadoInfo = false
        }
        poke()
    }

    func skip(_ ms: Int64) {
        guard let mp = mp, !req.isLive, mp.isSeekable else { return }
        let len = Int64(mp.media?.length.intValue ?? 0)
        let t = min(max(0, Int64(mp.time.intValue) + ms), len > 0 ? len - 1500 : Int64.max)
        mp.time = VLCTime(int: Int32(clamping: t))
        tempo = t
        nFlash += 1
        flash = Flash(tipo: ms < 0 ? "rew" : "fwd", n: nFlash)
        poke()
    }

    func irPara(_ fracao: Double) {
        guard let mp = mp, !req.isLive, duracao > 0, mp.isSeekable else { return }
        let t = Int64(min(1, max(0, fracao)) * Double(duracao))
        mp.time = VLCTime(int: Int32(clamping: t))
        tempo = t
        poke()
    }

    func arrastar(_ fracao: Double) {
        arrastando = true
        posArrasto = min(1, max(0, fracao))
        ultimoToque = relogio()
    }

    func soltar() {
        if !arrastando { return }
        arrastando = false
        irPara(posArrasto)
    }

    func doInicio() {
        mp?.time = VLCTime(int: 0)
        tempo = 0
        retomar = nil
        poke()
    }

    func playNext() {
        guard let n = proximo else { return }
        salvarProgresso()
        if let mp = mp {
            let len = Int64(mp.media?.length.intValue ?? 0)
            if len > 0 && len - Int64(mp.time.intValue) < 60_000 { Store.s.setProgress(req.key, len, len, req.showKey) }
        }
        if let r = PlayRequest.forEpisode(n) { start(r) }
    }

    func dispensarCreditos() {
        creditsDismissed = true
        cartaoProximo = false
    }

    func trocar(_ r: PlayRequest) {
        if fechado || mp == nil { return }
        salvarProgresso()
        retries = 0
        start(r)
    }

    func zap(_ dir: Int) {
        guard let list = req.channels, list.count >= 2 else { return }
        let i = list.firstIndex(of: req.item) ?? 0
        let next = list[((i + dir) % list.count + list.count) % list.count]
        retries = 0
        start(PlayRequest.forChannel(next, list))
        mostrarOsd(next.name)
    }

    func mostrarOsd(_ texto: String) {
        nOsd += 1
        osd = Osd(texto: texto, n: nOsd)
    }

    // ---------- Áudio, legendas, velocidade e imagem ----------

    private func pares(_ ids: [Any]?, _ nomes: [Any]?, legenda: Bool) -> [(Int32, String)] {
        let i = (ids as? [NSNumber])?.map { $0.int32Value } ?? []
        let n = (nomes as? [String]) ?? []
        var r: [(Int32, String)] = []
        var k = 0
        for (j, id) in i.enumerated() where id != -1 {
            k += 1
            r.append((id, nomeFaixa(j < n.count ? n[j] : nil, k, legenda)))
        }
        return r
    }

    func faixasAudio() -> [(Int32, String)] { pares(mp?.audioTrackIndexes, mp?.audioTrackNames, legenda: false) }
    func audioAtual() -> Int32 { mp?.currentAudioTrackIndex ?? -1 }

    func escolherAudio(_ id: Int32) {
        mp?.currentAudioTrackIndex = id
        faixas += 1
    }

    func faixasLegenda() -> [(Int32, String)] { pares(mp?.videoSubTitlesIndexes, mp?.videoSubTitlesNames, legenda: true) }
    func legendaAtual() -> Int32 { mp?.currentVideoSubTitleIndex ?? -1 }

    func escolherLegenda(_ id: Int32) {
        mp?.currentVideoSubTitleIndex = id
        faixas += 1
    }

    func carregarLegenda(_ f: URL) {
        _ = mp?.addPlaybackSlave(f, type: .subtitle, enforce: true)
        mostrarOsd("Legenda carregada")
        painel = nil
        faixas += 1
    }

    func definirVelocidade(_ r: Float) {
        mp?.rate = r
        velocidade = r
        mostrarOsd(r == 1 ? "Velocidade normal" : "Velocidade \(fmtVelocidade(r))")
    }

    func definirAspecto(_ a: String) {
        aspecto = a
        aplicarAspecto()
    }

    private func aplicarAspecto() {
        guard let mp = mp else { return }
        func definir(_ s: String?, _ campo: (UnsafeMutablePointer<CChar>?) -> Void) {
            guard let s = s else { campo(nil); return }
            let p = strdup(s)
            campo(p)
            free(p)
        }
        switch aspecto {
        case "fill":
            // preencher: corta o vídeo na proporção da tela
            let b = tela?.bounds.size ?? UIScreen.main.bounds.size
            let w = Int(max(b.width, b.height)), h = Int(min(b.width, b.height))
            definir(nil) { mp.videoAspectRatio = $0 }
            definir(w > 0 && h > 0 ? "\(w):\(h)" : nil) { mp.videoCropGeometry = $0 }
        case "16:9", "4:3":
            definir(nil) { mp.videoCropGeometry = $0 }
            definir(aspecto) { mp.videoAspectRatio = $0 }
        default:
            definir(nil) { mp.videoCropGeometry = $0 }
            definir(nil) { mp.videoAspectRatio = $0 }
        }
    }

    // ---------- Transmitir para a TV (Chromecast) ----------

    func abrirTransmitir() {
        Transmissao.s.iniciar()   // a busca de aparelhos só começa aqui
        abrirPainel("transmitir")
    }

    /// Manda o vídeo para o Chromecast (alvo) ou de volta para este aparelho (nil), continuando do mesmo ponto.
    func transmitir(_ alvo: VLCRendererItem?) {
        guard let mp = mp, !fechado else { return }
        painel = nil
        if alvo == nil ? !transmitindo : (transmitindo && Transmissao.s.ehOAtual(alvo!)) { return }
        salvarProgresso()
        let len = Int64(mp.media?.length.intValue ?? 0)
        let t: Int64 = req.isLive || len <= 0 ? -1 : max(0, Int64(mp.time.intValue) - 2000)
        let r = req
        ocupado = true
        // O VLC só liga o aparelho novo numa reprodução que começa depois da troca: para (fora da linha principal,
        // parar uma transmissão pode demorar), troca e reabre do mesmo ponto
        Task {
            await PlayerControle.parar(mp)
            guard !self.fechado, self.mp === mp else { return }
            let ok = mp.setRendererItem(alvo)
            if let a = alvo, ok { self.ligarTransmissao(a) } else { if alvo != nil { _ = mp.setRendererItem(nil) }; self.desligarTransmissao() }
            Transmissao.s.definirAtual(ok ? alvo : nil)
            if t >= 0 { self.reabrirEm = t }
            self.retries = 0
            self.start(r)
            self.mostrarOsd(self.cast != nil ? "Transmitindo para \(self.transmitindoPara ?? "a TV")" : "Assistindo neste aparelho")
        }
    }

    /// Para o VLC fora da linha principal e espera terminar.
    nonisolated private static func parar(_ mp: VLCMediaPlayer) async {
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            DispatchQueue.global(qos: .userInitiated).async {
                mp.stop()
                c.resume()
            }
        }
    }

    private func ligarTransmissao(_ a: VLCRendererItem) {
        cast = a
        transmitindoPara = Transmissao.nome(a)
    }

    private func desligarTransmissao() {
        cast = nil
        transmitindoPara = nil
    }

    // ---------- Sair do app / voltar ----------

    func aoParar() {
        guard let mp = mp, !fechado else { return }
        salvarProgresso()
        // transmitindo, a TV continua mostrando
        if transmitindo { return }
        if req.isLive { recomecarAoVoltar = true; mp.stop() } else if mp.isPlaying { mp.pause() }
    }

    func aoVoltar() {
        guard mp != nil, !fechado else { return }
        if recomecarAoVoltar { recomecarAoVoltar = false; start(req) } else { mostrarControles() }
    }

    // ---------- Encerrar ----------

    func fechar() {
        if fechado { return }
        salvarProgresso()
        fechado = true
        liberar()
        Estado.s.fecharPlayer()
    }

    /// A tela do player saiu: para o VLC fora da linha principal (parar um stream pode demorar).
    func liberar() {
        if !fechado { salvarProgresso() }
        fechado = true
        laco?.invalidate()
        laco = nil
        Transmissao.s.parar()
        cast = nil
        guard let mp = mp else { return }
        self.mp = nil
        DispatchQueue.global(qos: .utility).async {
            mp.stop()
            DispatchQueue.main.async { mp.drawable = nil }
        }
    }
}

func fmtVelocidade(_ r: Float) -> String {
    (r == r.rounded() ? String(Int(r)) : String(r)).replacingOccurrences(of: ".", with: ",") + "x"
}

private let idiomas: [String: String] = [
    "english": "Inglês", "eng": "Inglês", "en": "Inglês", "portuguese": "Português", "por": "Português", "pt": "Português",
    "pt-br": "Português (Brasil)", "brazilian portuguese": "Português (Brasil)", "spanish": "Espanhol", "spa": "Espanhol", "es": "Espanhol",
    "french": "Francês", "fre": "Francês", "fra": "Francês", "german": "Alemão", "ger": "Alemão", "deu": "Alemão",
    "italian": "Italiano", "ita": "Italiano", "japanese": "Japonês", "jpn": "Japonês", "korean": "Coreano", "kor": "Coreano",
    "chinese": "Chinês", "chi": "Chinês", "zho": "Chinês", "russian": "Russo", "rus": "Russo", "undetermined": "Indefinido", "und": "Indefinido",
]

private let colchetes = NSRegularExpression(#"\[(.+?)\]"#)
private let trilha = NSRegularExpression(#"^\s*(Track|Faixa|Pista)\s*\d+\s*"#)

/// "Track 1 - [English]" -> "Inglês"
func nomeFaixa(_ raw0: String?, _ indice: Int, _ legenda: Bool) -> String {
    let raw = raw0 ?? ""
    let m = colchetes.grupos(raw)
    var lang = m?[1]?.trimmingCharacters(in: .whitespaces)
    var semLang = raw
    if let inteiro = m?[0] { semLang = raw.replacingOccurrences(of: inteiro, with: "") }
    let ns = semLang as NSString
    var rest = trilha.stringByReplacingMatches(in: semLang, range: NSRange(location: 0, length: ns.length), withTemplate: "")
        .trimmingCharacters(in: CharacterSet(charactersIn: " -–"))
    if let l = lang { lang = idiomas[l.lowercased()] ?? (l.prefix(1).uppercased() + l.dropFirst()) }
    if let r = idiomas[rest.lowercased()] { rest = r }
    if let l = lang { return rest.isEmpty ? l : "\(l) (\(rest))" }
    return rest.isEmpty ? (legenda ? "Legenda \(indice)" : "Faixa \(indice)") : rest
}
