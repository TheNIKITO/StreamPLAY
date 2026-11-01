import SwiftUI
import ImageIO

/// Botão: o principal é vermelho, o outro é contornado.
struct Botao: View {
    let texto: String
    var icone: String? = nil
    var primario = false
    var habilitado = true
    var tamanho: CGFloat = 16
    var cheio = false
    let acao: () -> Void

    init(_ texto: String, icone: String? = nil, primario: Bool = false, habilitado: Bool = true, tamanho: CGFloat = 16,
         cheio: Bool = false, acao: @escaping () -> Void) {
        self.texto = texto
        self.icone = icone
        self.primario = primario
        self.habilitado = habilitado
        self.tamanho = tamanho
        self.cheio = cheio
        self.acao = acao
    }

    var body: some View {
        Button(action: { if habilitado { acao() } }) {
            HStack(spacing: 8) {
                if let i = icone { Image(systemName: i).font(.system(size: tamanho + 2, weight: .semibold)) }
                Text(texto).font(.system(size: tamanho, weight: .semibold)).lineLimit(1)
            }
            .foregroundColor(.white)
            .padding(.horizontal, 20)
            .padding(.vertical, 10)
            .frame(maxWidth: cheio ? .infinity : nil)
            .background(RoundedRectangle(cornerRadius: 6).fill(primario ? Cor.vermelho : Cor.fundo.opacity(0.4)))
            .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color.white.opacity(primario ? 0 : 0.55), lineWidth: 1.5))
            .opacity(habilitado ? 1 : 0.5)
            .contentShape(Rectangle())
        }
        .buttonStyle(Apertar())
    }
}

/// Encolhe um pouco ao apertar.
struct Apertar: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

struct IconeBotao: View {
    let icone: String
    var tamanho: CGFloat = 42
    var fundo: Color = .clear
    var contorno = false
    var cor: Color = .white
    let acao: () -> Void

    var body: some View {
        Button(action: acao) {
            Image(systemName: icone)
                .font(.system(size: tamanho * 0.45, weight: .semibold))
                .foregroundColor(cor)
                .frame(width: tamanho, height: tamanho)
                .background(Circle().fill(fundo))
                .overlay(Circle().stroke(Color(hex: 0xB3B3B3), lineWidth: contorno ? 2 : 0))
                .contentShape(Circle())
        }
        .buttonStyle(Apertar())
    }
}

struct Chip: View {
    let texto: String
    let selecionado: Bool
    let acao: () -> Void

    var body: some View {
        Button(action: acao) {
            Text(texto)
                .font(.system(size: 14, weight: .semibold))
                .lineLimit(1)
                .foregroundColor(selecionado ? .black : .white)
                .padding(.horizontal, 16)
                .padding(.vertical, 7)
                .background(Capsule().fill(selecionado ? Color.white : Color.clear))
                .overlay(Capsule().stroke(selecionado ? Color.white : Color(hex: 0x808080), lineWidth: 1.5))
        }
        .buttonStyle(Apertar())
    }
}

struct BarraProgresso: View {
    let fracao: Double
    var altura: CGFloat = 3

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Color(hex: 0x3A3A44))
                Capsule().fill(Cor.vermelho).frame(width: g.size.width * min(1, max(0, fracao)))
            }
        }
        .frame(height: altura)
    }
}

struct Selo: View {
    let texto: String

    var body: some View {
        Text(texto)
            .font(.system(size: 11, weight: .bold))
            .foregroundColor(.white)
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(RoundedRectangle(cornerRadius: 4).fill(Cor.vermelho))
    }
}

struct Mensagem: View {
    let titulo: String
    let texto: String

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(titulo).font(.system(size: 22, weight: .bold)).foregroundColor(.white)
            if !texto.isEmpty { Text(texto).font(.system(size: 15)).foregroundColor(Cor.sub).lineSpacing(5) }
        }
        .frame(maxWidth: 720, alignment: .leading)
    }
}

/// Título de fileira/seção com o traço vermelho.
struct TituloSecao: View {
    let texto: String
    var tamanho: CGFloat = 18

    var body: some View {
        HStack(spacing: 10) {
            RoundedRectangle(cornerRadius: 2).fill(Cor.vermelho).frame(width: 4, height: tamanho + 2)
            Text(texto).font(.system(size: tamanho, weight: .bold)).foregroundColor(.white).lineLimit(1)
        }
    }
}

// ---------- Imagens (capas e logos) guardadas no aparelho ----------

/// Baixa e guarda as capas: em disco (URLCache, 300 MB) e na memória já reduzidas ao tamanho da tela.
final class Imagens {
    static let s = Imagens()
    private let memoria = NSCache<NSString, UIImage>()
    private let sessao: URLSession

    private init() {
        memoria.totalCostLimit = 120 * 1024 * 1024
        let c = URLSessionConfiguration.default
        c.urlCache = URLCache(memoryCapacity: 16 * 1024 * 1024, diskCapacity: 300 * 1024 * 1024,
                              directory: Pastas.cache.appendingPathComponent("imagens", isDirectory: true))
        c.requestCachePolicy = .returnCacheDataElseLoad
        c.timeoutIntervalForRequest = 20
        c.httpMaximumConnectionsPerHost = 6
        sessao = URLSession(configuration: c)
    }

    func naMemoria(_ url: String, _ px: Int) -> UIImage? { memoria.object(forKey: "\(px)|\(url)" as NSString) }

    func carregar(_ url: String, _ px: Int) async -> UIImage? {
        let chave = "\(px)|\(url)" as NSString
        if let i = memoria.object(forKey: chave) { return i }
        let d: Data
        if url.lowercased().hasPrefix("data:"), let virgula = url.firstIndex(of: ",") {
            guard let b = Data(base64Encoded: String(url[url.index(after: virgula)...])) else { return nil }
            d = b
        } else {
            guard let u = URL(string: url) ?? URL(string: url.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""),
                  let resp = try? await sessao.data(from: u),
                  ((resp.1 as? HTTPURLResponse)?.statusCode ?? 200) < 400 else { return nil }
            d = resp.0
        }
        // reduz já na leitura (capas grandes gastariam muita memória)
        guard let fonte = CGImageSourceCreateWithData(d as CFData, nil) else { return nil }
        let op: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
                                   kCGImageSourceShouldCacheImmediately: true, kCGImageSourceThumbnailMaxPixelSize: max(24, px)]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(fonte, 0, op as CFDictionary) else { return nil }
        let img = UIImage(cgImage: cg)
        memoria.setObject(img, forKey: chave, cost: cg.bytesPerRow * cg.height)
        return img
    }
}

private let paleta: [Color] = [
    Color(hex: 0x8E2E3B), Color(hex: 0x3E5C9A), Color(hex: 0x2F7A5B), Color(hex: 0x7A4FA0),
    Color(hex: 0xA0662F), Color(hex: 0x2E7C8E), Color(hex: 0xA03E6E), Color(hex: 0x5D6B2F),
]

/// Capa / logotipo. Sem imagem (ou se ela não abrir), mostra o nome sobre um fundo colorido.
/// crop = false: a imagem inteira (logotipos de canal). px: tamanho máximo (lado maior) em pixels.
struct Imagem: View {
    let url: String?
    let nome: String?
    var crop = true
    var tamanhoNome: CGFloat = 14
    var padding: CGFloat = 0
    var px = 360
    var semFundo = false

    @State private var img: UIImage?
    @State private var falhou = false

    var body: some View {
        ZStack {
            if let i = img {
                if !semFundo { Cor.cartao }
                Image(uiImage: i)
                    .resizable()
                    .aspectRatio(contentMode: crop ? .fill : .fit)
                    .padding(padding)
            } else if falhou || (url ?? "").isEmpty {
                if !semFundo {
                    LinearGradient(colors: [paleta[abs((nome ?? "").hashValueEstavel) % paleta.count], Color(hex: 0x242424)],
                                   startPoint: .topLeading, endPoint: .bottomTrailing)
                    Text((nome ?? "").uppercased())
                        .font(.system(size: tamanhoNome, weight: .black))
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                        .lineLimit(4)
                        .padding(8)
                }
            } else if !semFundo {
                Cor.cartao
            }
        }
        .clipped()
        .task(id: url) {
            guard let u = url, !u.isEmpty else { img = nil; falhou = true; return }
            if let m = Imagens.s.naMemoria(u, px) { img = m; return }
            img = nil
            falhou = false
            let r = await Imagens.s.carregar(u, px)
            if Task.isCancelled { return }
            img = r
            falhou = r == nil
        }
    }
}

extension String {
    /// Hash que não muda entre aberturas do app (o hashValue do Swift muda), para a cor do cartão sem capa.
    var hashValueEstavel: Int {
        var h: Int32 = 0
        for u in utf16 { h = h &* 31 &+ Int32(u) }
        return Int(h & 0x7fffffff)
    }
}

/// Barra fina de andamento (0 a 1), vermelha sobre um trilho claro.
struct BarraAndamento: View {
    let p: Double

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 2).fill(Color.white.opacity(0.2))
                Rectangle().fill(Cor.vermelho).frame(width: g.size.width * min(1, max(0, p)))
            }
        }
        .frame(height: 4)
        .clipShape(RoundedRectangle(cornerRadius: 2))
    }
}

/// Janela escura no meio da tela (avisos, conta, novidades, categorias).
struct Janela<Conteudo: View>: View {
    var largura: CGFloat = 420
    let fechar: () -> Void
    @ViewBuilder let conteudo: () -> Conteudo

    var body: some View {
        ZStack {
            Color.black.opacity(0.6).ignoresSafeArea().onTapGesture(perform: fechar)
            VStack(alignment: .leading, spacing: 0) { conteudo() }
                .padding(20)
                .frame(maxWidth: largura, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 12).fill(Cor.painel))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Cor.linha, lineWidth: 1))
                .padding(24)
        }
        .transition(.opacity)
    }
}
