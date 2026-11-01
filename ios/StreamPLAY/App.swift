import SwiftUI
import AVFoundation

final class AppDelegate: NSObject, UIApplicationDelegate {
    /// O player trava a tela deitada no iPhone (veja Orientacao).
    static var orientacoes: UIInterfaceOrientationMask = .all

    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        AppDelegate.orientacoes
    }

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // o som sai mesmo com o iPhone no silencioso (como em qualquer app de vídeo)
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        return true
    }
}

@main
struct StreamPLAYApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @Environment(\.scenePhase) private var fase

    init() {
        Estado.s.iniciar()
    }

    var body: some Scene {
        WindowGroup {
            Raiz()
        }
        .onChange(of: fase) { f in
            // com o app na frente: confere conta, lista e atualização de tempos em tempos; ao sair, avisa o painel
            if f == .active { Estado.s.naFrente(true) } else if f == .background { Estado.s.naFrente(false) }
        }
    }
}
