import Foundation
import Network
import MapAroundShared

/// A minimal HTTP server on 127.0.0.1 that answers MapLibre's tile requests
/// (`/osm/{z}/{x}/{y}.pbf`) with vector tiles made by the shared Kotlin code.
final class TileServer {
    private let listener: NWListener
    private let queue = DispatchQueue(label: "app.maparound.tiles") // serial: MapTiles is not thread safe
    private let tiles: MapTiles

    init(tiles: MapTiles) throws {
        self.tiles = tiles
        let params = NWParameters.tcp
        params.requiredLocalEndpoint = NWEndpoint.hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: params)
    }

    func start(onReady: @escaping (UInt16) -> Void) {
        listener.stateUpdateHandler = { [weak self] state in
            if case .ready = state, let port = self?.listener.port?.rawValue {
                DispatchQueue.main.async { onReady(port) }
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { return }
            connection.start(queue: self.queue)
            self.receive(connection)
        }
        listener.start(queue: queue)
    }

    private func receive(_ connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { [weak self] data, _, _, _ in
            guard let self, let data, let request = String(data: data, encoding: .utf8) else {
                connection.cancel()
                return
            }
            let path = request.split(separator: " ").dropFirst().first.map(String.init) ?? "/"
            let body = self.handle(path)
            let head = "HTTP/1.1 \(body == nil ? "404 Not Found" : "200 OK")\r\n" +
                "Content-Type: application/x-protobuf\r\n" +
                "Content-Length: \(body?.count ?? 0)\r\n" +
                "Connection: close\r\n\r\n"
            var out = Data(head.utf8)
            if let body { out.append(body) }
            connection.send(content: out, completion: .contentProcessed { _ in connection.cancel() })
        }
    }

    private func handle(_ path: String) -> Data? {
        let parts = path.split(separator: "/")
        guard parts.count == 4, parts[0] == "osm",
              let z = Int32(parts[1]), let x = Int32(parts[2]),
              let y = Int32(parts[3].split(separator: ".").first ?? "") else { return nil }
        return tiles.tileData(z: z, x: x, y: y) as Data
    }
}
