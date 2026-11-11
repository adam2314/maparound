import UIKit

/// Draws the map's marker images from their names (see MarkerIcons.kt for the format), with the
/// same look as the Android map's markers. Every image is centred on its point except the pin,
/// whose tip is the point.
enum MarkerImages {
    private static let sep = "\u{1F}"
    private static let dark = UIColor(red: 40 / 255, green: 40 / 255, blue: 40 / 255, alpha: 1)
    private static let red = rgb(213, 0, 0)

    static func image(named name: String) -> UIImage? {
        let f = name.components(separatedBy: sep)
        switch f[0] {
        case "me":
            return dot(color: rgb(30, 136, 229))
        case "pin":
            return pin()
        case "place" where f.count >= 4:
            return badge(ring: color(f[1]), ringWidth: 2.5, body: .white, radius: 12,
                         symbol: f[2], symbolFont: .systemFont(ofSize: 14), symbolColor: dark,
                         label: f[3], labelColor: dark, alpha: 1)
        case "party" where f.count >= 5:
            let kind = f[1]
            let sos = kind == "sos"
            let body: UIColor = kind == "meet" ? rgb(198, 40, 40) : kind == "radio" ? rgb(97, 97, 97) : color(f[2])
            let symbol = sos ? "!" : String(f[3].prefix(1)).uppercased()
            return badge(ring: sos ? red : .white, ringWidth: sos ? 4 : 2, body: body, radius: 13,
                         symbol: kind == "meet" ? "" : symbol, symbolFont: .boldSystemFont(ofSize: 13), symbolColor: .white,
                         label: f[3], labelColor: sos ? red : dark, alpha: f[4] == "1" ? 140 / 255 : 1, flag: kind == "meet")
        case "wp" where f.count >= 3:
            let fill: UIColor = f[2] == "0" ? rgb(46, 125, 50) : f[2] == "2" ? rgb(198, 40, 40) : rgb(106, 27, 154)
            return disc(size: 26, shadow: false, rings: [(12, .white), (10, fill)], text: f[1], font: .boldSystemFont(ofSize: 12))
        case "letter" where f.count >= 3:
            return disc(size: 28, shadow: true, rings: [(12, .white), (10, color(f[2]))], text: f[1], font: .boldSystemFont(ofSize: 14))
        default:
            return nil
        }
    }

    /// A round marker with an optional label tag below; the circle's centre is the image's centre.
    private static func badge(ring: UIColor, ringWidth: CGFloat, body: UIColor, radius r: CGFloat,
                              symbol: String, symbolFont: UIFont, symbolColor: UIColor,
                              label: String, labelColor: UIColor, alpha: CGFloat, flag: Bool = false) -> UIImage {
        let labelAttrs: [NSAttributedString.Key: Any] = [
            .font: UIFont.boldSystemFont(ofSize: 11), .foregroundColor: labelColor.withAlphaComponent(alpha),
        ]
        let labelSize = label.isEmpty ? .zero : (label as NSString).size(withAttributes: labelAttrs)
        let labelW = labelSize.width + 8
        let labelH: CGFloat = label.isEmpty ? 0 : 16
        let gap: CGFloat = label.isEmpty ? 0 : 3
        let d = 2 * (r + ringWidth)
        let content = d + gap + labelH
        let topPad = content - d
        let size = CGSize(width: ceil(max(d, labelW)) + 2, height: ceil(topPad + content))
        return UIGraphicsImageRenderer(size: size).image { _ in
            let cx = size.width / 2
            let cy = topPad + d / 2
            ring.withAlphaComponent(alpha).setFill()
            UIBezierPath(ovalIn: CGRect(x: cx - d / 2, y: cy - d / 2, width: d, height: d)).fill()
            body.withAlphaComponent(alpha).setFill()
            UIBezierPath(ovalIn: CGRect(x: cx - r, y: cy - r, width: 2 * r, height: 2 * r)).fill()
            if flag {
                // A small white flag: the meeting point.
                UIColor.white.withAlphaComponent(alpha).set()
                let pole = UIBezierPath()
                pole.move(to: CGPoint(x: cx - 4, y: cy - 7))
                pole.addLine(to: CGPoint(x: cx - 4, y: cy + 7))
                pole.lineWidth = 2
                pole.stroke()
                let cloth = UIBezierPath()
                cloth.move(to: CGPoint(x: cx - 4, y: cy - 7))
                cloth.addLine(to: CGPoint(x: cx + 7, y: cy - 3.5))
                cloth.addLine(to: CGPoint(x: cx - 4, y: cy))
                cloth.close()
                cloth.fill()
            } else if !symbol.isEmpty {
                let attrs: [NSAttributedString.Key: Any] = [.font: symbolFont, .foregroundColor: symbolColor.withAlphaComponent(alpha)]
                let s = (symbol as NSString).size(withAttributes: attrs)
                (symbol as NSString).draw(at: CGPoint(x: cx - s.width / 2, y: cy - s.height / 2), withAttributes: attrs)
            }
            if !label.isEmpty {
                let top = cy + d / 2 + gap
                UIColor.white.withAlphaComponent(0.9 * alpha).setFill()
                UIBezierPath(roundedRect: CGRect(x: cx - labelW / 2, y: top, width: labelW, height: labelH), cornerRadius: 4).fill()
                (label as NSString).draw(at: CGPoint(x: cx - labelSize.width / 2, y: top + (labelH - labelSize.height) / 2),
                                         withAttributes: labelAttrs)
            }
        }
    }

    /// Concentric discs (radius, colour), outermost first, with centred white text.
    private static func disc(size: CGFloat, shadow: Bool, rings: [(CGFloat, UIColor)], text: String, font: UIFont) -> UIImage {
        UIGraphicsImageRenderer(size: CGSize(width: size, height: size)).image { _ in
            let c = size / 2
            if shadow {
                UIColor(white: 0, alpha: 70 / 255).setFill()
                UIBezierPath(ovalIn: CGRect(x: 1, y: 2, width: size - 2, height: size - 2)).fill()
            }
            for (r, color) in rings {
                color.setFill()
                UIBezierPath(ovalIn: CGRect(x: c - r, y: c - r, width: 2 * r, height: 2 * r)).fill()
            }
            let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor.white]
            let s = (text as NSString).size(withAttributes: attrs)
            (text as NSString).draw(at: CGPoint(x: c - s.width / 2, y: c - s.height / 2), withAttributes: attrs)
        }
    }

    /// The own position: a blue dot with a white rim.
    private static func dot(color: UIColor) -> UIImage {
        UIGraphicsImageRenderer(size: CGSize(width: 24, height: 24)).image { _ in
            UIColor(white: 0, alpha: 60 / 255).setFill()
            UIBezierPath(ovalIn: CGRect(x: 1, y: 2, width: 22, height: 22)).fill()
            UIColor.white.setFill()
            UIBezierPath(ovalIn: CGRect(x: 2, y: 2, width: 20, height: 20)).fill()
            color.setFill()
            UIBezierPath(ovalIn: CGRect(x: 5, y: 5, width: 14, height: 14)).fill()
        }
    }

    /// The dropped pin; its tip is at the bottom centre.
    private static func pin() -> UIImage {
        let w: CGFloat = 28, h: CGFloat = 40, r = w / 2
        return UIGraphicsImageRenderer(size: CGSize(width: w, height: h)).image { _ in
            rgb(211, 47, 47).setFill()
            let tip = UIBezierPath()
            tip.move(to: CGPoint(x: r, y: h))
            tip.addLine(to: CGPoint(x: r - r * 0.62, y: r * 1.55))
            tip.addLine(to: CGPoint(x: r + r * 0.62, y: r * 1.55))
            tip.close()
            tip.fill()
            UIBezierPath(ovalIn: CGRect(x: 1, y: 1, width: w - 2, height: w - 2)).fill()
            UIColor.white.setFill()
            UIBezierPath(ovalIn: CGRect(x: r - r * 0.38, y: r - r * 0.38, width: r * 0.76, height: r * 0.76)).fill()
        }
    }

    private static func rgb(_ r: CGFloat, _ g: CGFloat, _ b: CGFloat) -> UIColor {
        UIColor(red: r / 255, green: g / 255, blue: b / 255, alpha: 1)
    }

    /// "AARRGGBB" hex.
    private static func color(_ hex: String) -> UIColor {
        let v = UInt32(hex, radix: 16) ?? 0xFF808080
        return UIColor(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255,
                       blue: CGFloat(v & 0xFF) / 255, alpha: CGFloat((v >> 24) & 0xFF) / 255)
    }
}
