import UIKit
import MapLibre
import MapAroundShared

/// The MapLibre view behind the shared map screen (Kotlin's NativeMapView). Kotlin's
/// MapLibreSurface decides everything — style, overlays as GeoJSON, camera; this class only
/// passes it on to MLNMapView and reports taps and gestures back.
final class MapLibreMap: NSObject, NativeMapView, MLNMapViewDelegate {
    private let mapView: MLNMapView
    private var callbacks: NativeMapCallbacks?
    /// The latest GeoJSON of every overlay source, applied again whenever a style has loaded.
    private var geoJson: [String: String] = [:]
    private var styleLoaded = false
    private var styleFiles: [URL] = []
    private var styleCount = 0

    var view: UIView { mapView }

    override init() {
        mapView = MLNMapView(frame: .zero)
        super.init()
        mapView.delegate = self
        mapView.logoView.isHidden = true
        let long = UILongPressGestureRecognizer(target: self, action: #selector(longPress(_:)))
        mapView.addGestureRecognizer(long)
        let tap = UITapGestureRecognizer(target: self, action: #selector(tap(_:)))
        // The map's own double tap (zoom in) goes first.
        for r in mapView.gestureRecognizers ?? [] {
            if let t = r as? UITapGestureRecognizer, t.numberOfTapsRequired == 2 { tap.require(toFail: t) }
        }
        mapView.addGestureRecognizer(tap)
    }

    // MARK: NativeMap

    func setStyle(json: String) {
        styleCount += 1
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("maparound-style-\(styleCount).json")
        do {
            try Data(json.utf8).write(to: url)
        } catch {
            NSLog("MapAround: cannot write the map style: \(error)")
            return
        }
        styleFiles.append(url)
        styleLoaded = false
        mapView.styleURL = url
    }

    func setGeoJson(sourceId: String, json: String) {
        geoJson[sourceId] = json
        if styleLoaded, let style = mapView.style { apply(sourceId, json, style) }
    }

    func camera() -> NativeCamera {
        let c = mapView.centerCoordinate
        return NativeCamera(lat: c.latitude, lon: c.longitude, zoom: mapView.zoomLevel, bearing: mapView.direction)
    }

    func viewHeight() -> Double {
        Double(mapView.bounds.height)
    }

    func moveCamera(lat: Double, lon: Double, zoom: Double, animated: Bool) {
        mapView.setCenter(CLLocationCoordinate2D(latitude: lat, longitude: lon), zoomLevel: zoom, animated: animated)
    }

    func fitBounds(south: Double, west: Double, north: Double, east: Double, animated: Bool) {
        let bounds = MLNCoordinateBounds(
            sw: CLLocationCoordinate2D(latitude: south, longitude: west),
            ne: CLLocationCoordinate2D(latitude: north, longitude: east))
        mapView.setVisibleCoordinateBounds(bounds, edgePadding: UIEdgeInsets(top: 100, left: 48, bottom: 160, right: 48),
                                           animated: animated, completionHandler: nil)
    }

    func setCallbacks(callbacks: NativeMapCallbacks?) {
        self.callbacks = callbacks
    }

    /// The points of interest drawn within `radiusPx` points of (lat, lon), as JSON (see NativeMap.kt).
    func poisNear(lat: Double, lon: Double, radiusPx: Double) -> String {
        guard let style = mapView.style else { return "[]" }
        let ids = Set(style.layers.compactMap { layer -> String? in
            guard let symbols = layer as? MLNSymbolStyleLayer, symbols.sourceLayerIdentifier == "poi" else { return nil }
            return symbols.identifier
        })
        guard !ids.isEmpty else { return "[]" }
        let p = mapView.convert(CLLocationCoordinate2D(latitude: lat, longitude: lon), toPointTo: mapView)
        let r = CGFloat(radiusPx)
        var out: [[String: Any]] = []
        for feature in mapView.visibleFeatures(in: CGRect(x: p.x - r, y: p.y - r, width: 2 * r, height: 2 * r), styleLayerIdentifiers: ids) {
            guard feature is MLNPointFeature else { continue }
            var props: [String: String] = [:]
            for (key, value) in feature.attributes { props[key] = "\(value)" }
            out.append(["lat": feature.coordinate.latitude, "lon": feature.coordinate.longitude, "p": props])
        }
        guard let data = try? JSONSerialization.data(withJSONObject: out), let json = String(data: data, encoding: .utf8) else { return "[]" }
        return json
    }

    // MARK: MLNMapViewDelegate

    func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
        styleLoaded = true
        for (id, json) in geoJson { apply(id, json, style) }
        // Earlier style files are no longer needed.
        while styleFiles.count > 1 {
            try? FileManager.default.removeItem(at: styleFiles.removeFirst())
        }
    }

    func mapView(_ mapView: MLNMapView, regionWillChangeWith reason: MLNCameraChangeReason, animated: Bool) {
        let gestures: MLNCameraChangeReason = [.gesturePan, .gesturePinch, .gestureRotate, .gestureZoomIn,
                                               .gestureZoomOut, .gestureOneFingerZoom, .gestureTilt]
        if !reason.isDisjoint(with: gestures) { callbacks?.onUserGesture() }
    }

    /// Marker images are drawn from their names (MarkerIcons.kt) when the map first needs them.
    func mapView(_ mapView: MLNMapView, didFailToLoadImage imageName: String) -> UIImage? {
        MarkerImages.image(named: imageName)
    }

    // MARK: Gestures

    @objc private func tap(_ r: UITapGestureRecognizer) {
        guard r.state == .ended else { return }
        let p = r.location(in: mapView)
        let c = mapView.convert(p, toCoordinateFrom: mapView)
        callbacks?.onTap(lat: c.latitude, lon: c.longitude, tapped: tapped(at: p))
    }

    /// A long press, then the finger moving on (dragging a route stop) until it's lifted. The map
    /// doesn't pan meanwhile: its own pan can't start while this recognizer holds the touch.
    @objc private func longPress(_ r: UILongPressGestureRecognizer) {
        let c = mapView.convert(r.location(in: mapView), toCoordinateFrom: mapView)
        switch r.state {
        case .began: callbacks?.onLongPress(lat: c.latitude, lon: c.longitude)
        case .changed: callbacks?.onLongPressMove(lat: c.latitude, lon: c.longitude)
        case .ended, .cancelled: callbacks?.onLongPressEnd(lat: c.latitude, lon: c.longitude)
        default: break
        }
    }

    /// The `tap` property of the topmost marker near the finger (layers named "tap-…", see MapStyle.kt).
    private func tapped(at p: CGPoint) -> String? {
        guard let style = mapView.style else { return nil }
        let ids = Set(style.layers.map(\.identifier).filter { $0.hasPrefix("tap-") })
        guard !ids.isEmpty else { return nil }
        let rect = CGRect(x: p.x - 22, y: p.y - 22, width: 44, height: 44)
        for feature in mapView.visibleFeatures(in: rect, styleLayerIdentifiers: ids) {
            if let tap = feature.attribute(forKey: "tap") as? String { return tap }
        }
        return nil
    }

    private func apply(_ id: String, _ json: String, _ style: MLNStyle) {
        guard let source = style.source(withIdentifier: id) as? MLNShapeSource else { return }
        do {
            source.shape = try MLNShape(data: Data(json.utf8), encoding: String.Encoding.utf8.rawValue)
        } catch {
            NSLog("MapAround: bad GeoJSON for \(id): \(error)")
        }
    }
}
