import MetalKit
import simd
import MapLibre
import MapAroundShared

/// Phase 0 spike (c): one map tile's terrain, meshed by the shared Kotlin code (the mesh the
/// Android 3D view draws), drawn with Metal and draped with a MapLibre snapshot of the same tile.
/// The camera circles the tile slowly.
final class TerrainView: MTKView {
    private var renderer: TerrainRenderer?

    init(styleURL: URL) {
        let device = MTLCreateSystemDefaultDevice()
        super.init(frame: .zero, device: device)
        colorPixelFormat = .bgra8Unorm
        depthStencilPixelFormat = .depth32Float
        clearColor = MTLClearColor(red: 0.80, green: 0.88, blue: 0.95, alpha: 1)
        preferredFramesPerSecond = 60
        if let device {
            renderer = TerrainRenderer(view: self, device: device, styleURL: styleURL)
            delegate = renderer
        } else {
            NSLog("MapAround 3D: no Metal device")
        }
    }

    required init(coder: NSCoder) {
        fatalError("not used")
    }
}

private struct Uniforms {
    var mvp: simd_float4x4
    var eye: SIMD4<Float>
    var light: SIMD4<Float>
    var sky: SIMD4<Float>
    var params: SIMD4<Float>
    var heights: SIMD4<Float>
}

final class TerrainRenderer: NSObject, MTKViewDelegate {
    private let queue: MTLCommandQueue
    private let pipeline: MTLRenderPipelineState
    private let depthState: MTLDepthStencilState
    private let sampler: MTLSamplerState
    private let vertices: MTLBuffer
    private let indices: MTLBuffer
    private let indexCount: Int
    private let mesh: TerrainTileMesh
    private var texture: MTLTexture
    private var hasTexture = false
    private var snapshotter: MLNMapSnapshotter?
    private let started = CACurrentMediaTime()
    private let exaggeration: Float = 1.5

    init?(view: MTKView, device: MTLDevice, styleURL: URL) {
        // Vaduz and the slopes above it; elevation from the bundled N47E009.hgt.
        mesh = TerrainTileMesh(demDir: Bundle.main.resourcePath ?? "", lat: 47.12, lon: 9.54, zoom: 12, gridSize: 129)
        NSLog("MapAround 3D: tile %d/%d/%d, terrain %@, heights %.0f–%.0f m, %d indices",
              mesh.tile.z, mesh.tile.x, mesh.tile.y, mesh.hasTerrain ? "yes" : "no",
              mesh.minHeight, mesh.maxHeight, mesh.indexCount)
        guard let queue = device.makeCommandQueue(),
              let library = device.makeDefaultLibrary(),
              let vb = Self.buffer(device, mesh.vertexData),
              let ib = Self.buffer(device, mesh.indexData)
        else { return nil }
        self.queue = queue
        vertices = vb
        indices = ib
        indexCount = Int(mesh.indexCount)

        let vd = MTLVertexDescriptor()
        vd.attributes[0].format = .float3; vd.attributes[0].offset = 0; vd.attributes[0].bufferIndex = 0
        vd.attributes[1].format = .float3; vd.attributes[1].offset = 12; vd.attributes[1].bufferIndex = 0
        vd.attributes[2].format = .float2; vd.attributes[2].offset = 24; vd.attributes[2].bufferIndex = 0
        vd.layouts[0].stride = Int(TileMesh.shared.STRIDE) * 4

        let pd = MTLRenderPipelineDescriptor()
        pd.vertexFunction = library.makeFunction(name: "terrainVertex")
        pd.fragmentFunction = library.makeFunction(name: "terrainFragment")
        pd.vertexDescriptor = vd
        pd.colorAttachments[0].pixelFormat = view.colorPixelFormat
        pd.depthAttachmentPixelFormat = view.depthStencilPixelFormat
        do {
            pipeline = try device.makeRenderPipelineState(descriptor: pd)
        } catch {
            NSLog("MapAround 3D: pipeline failed: %@", "\(error)")
            return nil
        }
        let dd = MTLDepthStencilDescriptor()
        dd.depthCompareFunction = .less
        dd.isDepthWriteEnabled = true
        guard let ds = device.makeDepthStencilState(descriptor: dd) else { return nil }
        depthState = ds
        let sd = MTLSamplerDescriptor()
        sd.minFilter = .linear; sd.magFilter = .linear; sd.mipFilter = .linear
        sd.sAddressMode = .clampToEdge; sd.tAddressMode = .clampToEdge
        sd.maxAnisotropy = 8
        guard let sm = device.makeSamplerState(descriptor: sd) else { return nil }
        sampler = sm
        // A white pixel until the map picture arrives.
        let td = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba8Unorm, width: 1, height: 1, mipmapped: false)
        guard let white = device.makeTexture(descriptor: td) else { return nil }
        var px: [UInt8] = [255, 255, 255, 255]
        white.replace(region: MTLRegionMake2D(0, 0, 1, 1), mipmapLevel: 0, withBytes: &px, bytesPerRow: 4)
        texture = white
        super.init()
        snapshot(device: device, styleURL: styleURL)
    }

    /// The same style MapLibre draws, rendered for exactly this tile (square, Web Mercator).
    private func snapshot(device: MTLDevice, styleURL: URL) {
        let options = MLNMapSnapshotOptions(styleURL: styleURL, camera: MLNMapCamera(), size: CGSize(width: 1024, height: 1024))
        options.coordinateBounds = MLNCoordinateBounds(
            sw: CLLocationCoordinate2D(latitude: mesh.south, longitude: mesh.west),
            ne: CLLocationCoordinate2D(latitude: mesh.north, longitude: mesh.east))
        options.scale = 1
        let snapshotter = MLNMapSnapshotter(options: options)
        self.snapshotter = snapshotter
        snapshotter.start { [weak self] snapshot, error in
            guard let self else { return }
            self.snapshotter = nil
            guard let cg = snapshot?.image.cgImage else {
                NSLog("MapAround 3D: snapshot failed: %@", "\(String(describing: error))")
                return
            }
            let loader = MTKTextureLoader(device: device)
            do {
                self.texture = try loader.newTexture(cgImage: cg, options: [
                    .origin: MTKTextureLoader.Origin.topLeft, .SRGB: false, .generateMipmaps: true,
                ])
                self.hasTexture = true
                NSLog("MapAround 3D: map picture %dx%d", cg.width, cg.height)
            } catch {
                NSLog("MapAround 3D: texture failed: %@", "\(error)")
            }
        }
    }

    /// Kotlin's NSData arrives in Swift as Data.
    private static func buffer(_ device: MTLDevice, _ data: Data) -> MTLBuffer? {
        data.withUnsafeBytes { raw in
            raw.baseAddress.flatMap { device.makeBuffer(bytes: $0, length: raw.count) }
        }
    }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    func draw(in view: MTKView) {
        guard let pass = view.currentRenderPassDescriptor, let drawable = view.currentDrawable,
              let cmd = queue.makeCommandBuffer(), let enc = cmd.makeRenderCommandEncoder(descriptor: pass)
        else { return }
        let size = view.drawableSize
        let aspect = Float(size.width / max(1, size.height))
        let e = exaggeration
        let mid = (mesh.minHeight + mesh.maxHeight) / 2 * e
        let target = SIMD3<Float>(0, 0, mid)
        // Circle the tile slowly, 35° above the horizon.
        let t = Float(CACurrentMediaTime() - started)
        let az = (200 + t * 6) * .pi / 180
        let up = Float(35) * .pi / 180
        let dist = mesh.sizeM * (aspect < 1 ? 1.45 : 1.05)
        let eye = target + dist * SIMD3<Float>(cos(up) * -sin(az), cos(up) * -cos(az), sin(up))
        let proj = perspective(fovy: 50 * .pi / 180, aspect: aspect, near: max(1, dist * 0.02), far: dist * 4)
        let viewM = lookAt(eye: eye, center: target, up: SIMD3<Float>(0, 0, 1))
        var u = Uniforms(
            mvp: proj * viewM,
            eye: SIMD4<Float>(eye, 1),
            light: SIMD4<Float>(normalize(SIMD3<Float>(-1, 1, 1.4)), 0),
            sky: SIMD4<Float>(0.80, 0.88, 0.95, 1),
            params: SIMD4<Float>(e, dist * 0.9, dist * 3, hasTexture ? 1 : 0),
            heights: SIMD4<Float>(mesh.minHeight, mesh.maxHeight, 0, 0))
        enc.setRenderPipelineState(pipeline)
        enc.setDepthStencilState(depthState)
        enc.setCullMode(.none)
        enc.setVertexBuffer(vertices, offset: 0, index: 0)
        enc.setVertexBytes(&u, length: MemoryLayout<Uniforms>.stride, index: 1)
        enc.setFragmentBytes(&u, length: MemoryLayout<Uniforms>.stride, index: 1)
        enc.setFragmentTexture(texture, index: 0)
        enc.setFragmentSamplerState(sampler, index: 0)
        enc.drawIndexedPrimitives(type: .triangle, indexCount: indexCount, indexType: .uint16, indexBuffer: indices, indexBufferOffset: 0)
        enc.endEncoding()
        cmd.present(drawable)
        cmd.commit()
    }

    private func perspective(fovy: Float, aspect: Float, near: Float, far: Float) -> simd_float4x4 {
        let y = 1 / tan(fovy / 2)
        let x = y / aspect
        let z = far / (near - far)
        return simd_float4x4(columns: (
            SIMD4<Float>(x, 0, 0, 0), SIMD4<Float>(0, y, 0, 0),
            SIMD4<Float>(0, 0, z, -1), SIMD4<Float>(0, 0, z * near, 0)))
    }

    private func lookAt(eye: SIMD3<Float>, center: SIMD3<Float>, up: SIMD3<Float>) -> simd_float4x4 {
        let f = normalize(center - eye)
        let s = normalize(cross(f, up))
        let u = cross(s, f)
        return simd_float4x4(columns: (
            SIMD4<Float>(s.x, u.x, -f.x, 0), SIMD4<Float>(s.y, u.y, -f.y, 0),
            SIMD4<Float>(s.z, u.z, -f.z, 0), SIMD4<Float>(-dot(s, eye), -dot(u, eye), dot(f, eye), 1)))
    }
}
