import SwiftUI
import MapAroundShared

/// The iPhone app: the shared Compose screens (Kotlin), with MapLibre drawing the map from the
/// vector tiles our own tile server makes out of the mapsforge maps in Documents/maps.
@main
struct MapAroundApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            if model.ready {
                SharedRoot().ignoresSafeArea()
            } else {
                ProgressView()
            }
        }
    }
}

/// Starts the tile server; the screens come once MapLibre has an address to ask.
final class AppModel: ObservableObject {
    @Published var ready = false
    private var server: TileServer?

    init() {
        let tiles = IosApp.shared.tiles
        do {
            let server = try TileServer(tiles: tiles)
            self.server = server
            server.start { [weak self] port in
                tiles.baseUrl = "http://127.0.0.1:\(port)"
                self?.ready = true
            }
        } catch {
            NSLog("MapAround: tile server failed: \(error)")
            ready = true
        }
    }
}

/// The shared app (MainViewController.kt) with MapLibre maps made here.
struct SharedRoot: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(newMap: { MapLibreMap() })
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
