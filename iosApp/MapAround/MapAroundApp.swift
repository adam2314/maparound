import SwiftUI
import MapLibre
import MapAroundShared

/// Phase 0 shell: the bundled Liechtenstein mapsforge map, turned into vector tiles by the shared
/// Kotlin code and drawn by MapLibre.
@main
struct MapAroundApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            ZStack(alignment: .bottom) {
                if let style = model.styleURL {
                    MapView(styleURL: style).ignoresSafeArea()
                } else {
                    ProgressView()
                }
                Text(model.status)
                    .font(.caption)
                    .padding(6)
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 6))
                    .padding(.bottom, 24)
            }
        }
    }
}

final class AppModel: ObservableObject {
    @Published var styleURL: URL?
    @Published var status = "Starting…"
    private var server: TileServer?

    init() {
        guard let path = Bundle.main.path(forResource: "liechtenstein", ofType: "map") else {
            status = "No map in the app bundle"
            return
        }
        let tiles = MapTiles(paths: [path])
        guard tiles.mapCount > 0 else {
            status = "Could not read the map"
            return
        }
        do {
            let server = try TileServer(tiles: tiles)
            self.server = server
            server.start { [weak self] port in
                self?.styleURL = SpikeStyle.write(port: port)
                self?.status = "mapsforge → vector tiles → MapLibre · port \(port)"
            }
        } catch {
            status = "Tile server failed: \(error)"
        }
    }
}

struct MapView: UIViewRepresentable {
    let styleURL: URL

    func makeUIView(context: Context) -> MLNMapView {
        let map = MLNMapView(frame: .zero, styleURL: styleURL)
        map.setCenter(CLLocationCoordinate2D(latitude: 47.141, longitude: 9.521), zoomLevel: 13.5, animated: false)
        return map
    }

    func updateUIView(_ uiView: MLNMapView, context: Context) {}
}
