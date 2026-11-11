import Foundation
import Network
import MapAroundShared

/// A minimal HTTP server on 127.0.0.1 that answers MapLibre's requests with what the shared
/// Kotlin TileService makes (vector tiles from the mapsforge maps, see TileService.kt).
final class TileServer {
    private let listener: NWListener
    private let queue = DispatchQueue(label: "app.maparound.tiles") // serial: TileService answers one request at a time
    private let tiles: TileService

    init(tiles: TileService) throws {
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
            let answer = self.tiles.respondData(path: path)
            let body = answer.map { $0.data as Data }
            // Tile URLs carry a key of the map files, so a tile never changes: MapLibre may keep it.
            var head = "HTTP/1.1 \(answer == nil ? "404 Not Found" : "200 OK")\r\n"
            head += "Content-Type: \(answer?.contentType ?? "text/plain")\r\n"
            head += "Content-Length: \(body?.count ?? 0)\r\n"
            if answer != nil { head += "Cache-Control: max-age=31536000\r\n" }
            head += "Connection: close\r\n\r\n"
            var out = Data(head.utf8)
            if let body { out.append(body) }
            connection.send(content: out, completion: .contentProcessed { _ in connection.cancel() })
        }
    }
}
