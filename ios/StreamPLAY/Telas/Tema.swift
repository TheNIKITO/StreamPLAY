import SwiftUI

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255, opacity: alpha)
    }
}

// identidade 1.3.0: fundo quase preto azulado, vermelho de destaque (igual ao PC e ao Android)
enum Cor {
    static let fundo = Color(hex: 0x0D0D11)
    static let painel = Color(hex: 0x141419)
    static let cartao = Color(hex: 0x1A1A20)
    static let hover = Color(hex: 0x2A2A32)
    static let sub = Color(hex: 0xB3B3BC)
    static let dim = Color(hex: 0x8A8A94)
    static let vermelho = Color(hex: 0xE50914)
    static let vermelhoClaro = Color(hex: 0xFF2A35)
    static let linha = Color.white.opacity(0.2)
    static let link = Color(hex: 0xD0D0D8)
    static let verde = Color(hex: 0x46D369)
    static let texto = Color(hex: 0xE5E5E5)
    static let janela = Color(hex: 0x181818)
}

/// Medidas da tela: celular (estreito) ou iPad/celular deitado (largo).
struct Medidas: Equatable {
    var largura: CGFloat = 390
    var altura: CGFloat = 844
    var largo: Bool { largura >= 600 }
    var margem: CGFloat { largo ? 32 : 16 }
    var cartao: CGFloat { largo ? 128 : 108 }
}

private struct ChaveMedidas: EnvironmentKey { static let defaultValue = Medidas() }

extension EnvironmentValues {
    var medidas: Medidas {
        get { self[ChaveMedidas.self] }
        set { self[ChaveMedidas.self] = newValue }
    }
}

/// Ícones (símbolos do próprio iOS).
enum Icone {
    static let inicio = "house"
    static let aoVivo = "dot.radiowaves.left.and.right"
    static let filme = "film"
    static let serie = "tv"
    static let marcador = "bookmark"
    static let sino = "bell"
    static let usuario = "person.crop.circle"
    static let busca = "magnifyingglass"
    static let fechar = "xmark"
    static let play = "play.fill"
    static let pausa = "pause.fill"
    static let voltar10 = "gobackward.10"
    static let avancar10 = "goforward.10"
    static let proximo = "forward.end.fill"
    static let episodios = "list.bullet"
    static let legendas = "captions.bubble"
    static let velocidade = "speedometer"
    static let canais = "tv"
    static let transmitir = "tv.and.mediabox"
    static let acima = "chevron.up"
    static let abaixo = "chevron.down"
    static let voltar = "chevron.left"
    static let info = "info.circle"
    static let mais = "plus"
    static let check = "checkmark"
    static let recomecar = "arrow.counterclockwise"
    static let estrela = "star.fill"
    static let estrelaContorno = "star"
}

/// ▶ STREAMPLAY (play vermelho + STREAM branco + PLAY vermelho), na altura pedida.
struct Logo: View {
    var altura: CGFloat = 30

    var body: some View {
        HStack(spacing: altura * 0.28) {
            Image(systemName: "play.fill")
                .resizable()
                .scaledToFit()
                .frame(width: altura * 0.95, height: altura * 0.95)
                .foregroundColor(Cor.vermelho)
            (Text("STREAM").foregroundColor(.white) + Text("PLAY").foregroundColor(Cor.vermelho))
                .font(.system(size: altura * 0.8, weight: .black))
                .lineLimit(1)
                .fixedSize()
        }
    }
}

/// Traço vermelho + "F I L M E" acima do título.
struct RotuloTipo: View {
    let texto: String

    var body: some View {
        HStack(spacing: 10) {
            RoundedRectangle(cornerRadius: 2).fill(Cor.vermelho).frame(width: 4, height: 18)
            Text(texto).font(.system(size: 14, weight: .semibold)).foregroundColor(Color(hex: 0xE8E8EE))
        }
    }
}
