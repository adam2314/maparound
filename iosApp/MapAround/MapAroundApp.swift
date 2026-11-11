import SwiftUI
import MapLibre
import MapAroundShared

/// Phase 0 shell: the bundled Liechtenstein mapsforge map, turned into vector tiles by the shared
/// Kotlin code and drawn by MapLibre, inside the shared Compose screen.
@main
struct MapAroundApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            if let style = model.styleURL {
                SharedScreen(styleURL: style, subtitle: model.status).ignoresSafeArea()
            } else {
                VStack(spacing: 12) {
                    ProgressView()
                    Text(model.status).font(.caption)
                }
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
                self?.status = "Compose + MapLibre · mapsforge tiles on port \(port)"
                self?.styleURL = SpikeStyle.write(port: port)
            }
        } catch {
            status = "Tile server failed: \(error)"
        }
    }
}

/// The shared Compose screen (Kotlin) with the MapLibre map inside it.
struct SharedScreen: UIViewControllerRepresentable {
    let styleURL: URL
    let subtitle: String

    func makeUIViewController(context: Context) -> UIViewController {
        let surface = MapLibreSurface(styleURL: styleURL)
        // CI also launches the app with MAPAROUND_DEMO=1 (the screen moves the map by itself)
        // and MAPAROUND_DEMO=3d (it opens in the 3D view).
        let demo = ProcessInfo.processInfo.environment["MAPAROUND_DEMO"]
        let style = styleURL
        return MainViewControllerKt.MainViewController(
            map: surface, events: surface.events, subtitle: subtitle,
            demo: demo == "1", start3d: demo == "3d",
            makeMapView: { surface.view },
            makeTerrainView: { TerrainView(styleURL: style) }
        )
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}

/// The MapLibre map as the phase 0 screen's SpikeMapSurface; reports the camera to MapEvents.
final class MapLibreSurface: NSObject, SpikeMapSurface, MLNMapViewDelegate {
    let view: MLNMapView
    let events = MapEvents()

    init(styleURL: URL) {
        view = MLNMapView(frame: .zero, styleURL: styleURL)
        super.init()
        view.delegate = self
        view.setCenter(CLLocationCoordinate2D(latitude: 47.141, longitude: 9.521), zoomLevel: 13.5, animated: false)
        report()
    }

    func moveTo(lat: Double, lon: Double, zoom: Double) {
        view.setCenter(CLLocationCoordinate2D(latitude: lat, longitude: lon), zoomLevel: zoom, animated: true)
    }

    func zoomBy(delta: Double) {
        view.setZoomLevel(view.zoomLevel + delta, animated: true)
    }

    func resetNorth() {
        view.resetNorth()
    }

    func mapViewRegionIsChanging(_ mapView: MLNMapView) {
        report()
    }

    func mapView(_ mapView: MLNMapView, regionDidChangeAnimated animated: Bool) {
        report()
        let c = view.centerCoordinate
        NSLog("MapAround camera %.4f %.4f zoom %.2f", c.latitude, c.longitude, view.zoomLevel)
    }

    private func report() {
        let c = view.centerCoordinate
        events.cameraChanged(lat: c.latitude, lon: c.longitude, zoom: view.zoomLevel, bearing: view.direction)
    }
}
