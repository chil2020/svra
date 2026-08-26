import Foundation
import CoreGraphics
import CoreText
import ImageIO
import UniformTypeIdentifiers

let W = 2500.0, H = 843.0
let cell = W / 3.0

// 專案既有的配色（見運轉手冊的 tokens）
func rgb(_ r: Int, _ g: Int, _ b: Int) -> CGColor {
    CGColor(red: Double(r)/255, green: Double(g)/255, blue: Double(b)/255, alpha: 1)
}
let ground = rgb(0xF6, 0xF8, 0xF6)
let ink    = rgb(0x17, 0x1C, 0x19)
let accent = rgb(0x2C, 0x6A, 0x57)
let rule   = rgb(0xDC, 0xE2, 0xDD)

let cs = CGColorSpaceCreateDeviceRGB()
guard let ctx = CGContext(data: nil, width: Int(W), height: Int(H),
                          bitsPerComponent: 8, bytesPerRow: 0, space: cs,
                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
    fatalError("context")
}
ctx.setAllowsAntialiasing(true)

// 底
ctx.setFillColor(ground)
ctx.fill(CGRect(x: 0, y: 0, width: W, height: H))

// 由上而下的座標換算（CG 的原點在左下）
func y(_ fromTop: Double) -> Double { H - fromTop }

// 分隔線
ctx.setFillColor(rule)
for i in 1..<3 {
    ctx.fill(CGRect(x: cell * Double(i) - 1.5, y: 110, width: 3, height: H - 220))
}

// 置中文字
func text(_ s: String, _ font: String, _ size: Double, _ color: CGColor,
          centerX: Double, baselineFromTop: Double) {
    let f = CTFontCreateWithName(font as CFString, size, nil)
    // 不 import AppKit，所以用 CoreText 的原生鍵，不用 .font / .foregroundColor 那組便利常數
    let attrs: [NSAttributedString.Key: Any] = [
        NSAttributedString.Key(kCTFontAttributeName as String): f,
        NSAttributedString.Key(kCTForegroundColorAttributeName as String): color,
        NSAttributedString.Key(kCTKernAttributeName as String): size * 0.04,
    ]
    let line = CTLineCreateWithAttributedString(
        NSAttributedString(string: s, attributes: attrs))
    let bounds = CTLineGetBoundsWithOptions(line, .useOpticalBounds)
    ctx.textPosition = CGPoint(x: centerX - bounds.width / 2 - bounds.origin.x,
                               y: y(baselineFromTop))
    CTLineDraw(line, ctx)
}

// ── 圖示：清單 ──
func listIcon(cx: Double, top: Double) {
    let rowH = 46.0, gap = 28.0
    let widths = [190.0, 190.0, 128.0]
    ctx.setFillColor(accent)
    for (i, w) in widths.enumerated() {
        let ry = y(top + Double(i) * (rowH + gap))
        ctx.fillEllipse(in: CGRect(x: cx - 118, y: ry - 26, width: 26, height: 26))
        let bar = CGPath(roundedRect: CGRect(x: cx - 70, y: ry - 24, width: w, height: 22),
                         cornerWidth: 11, cornerHeight: 11, transform: nil)
        ctx.addPath(bar); ctx.fillPath()
    }
}

// ── 圖示：行事曆 ──
func calendarIcon(cx: Double, top: Double) {
    let w = 226.0, h = 214.0
    let box = CGRect(x: cx - w/2, y: y(top + h), width: w, height: h)
    ctx.setStrokeColor(accent); ctx.setLineWidth(14)
    let outline = CGPath(roundedRect: box.insetBy(dx: 7, dy: 7),
                         cornerWidth: 24, cornerHeight: 24, transform: nil)
    ctx.addPath(outline); ctx.strokePath()
    // 上緣的橫槓
    ctx.setFillColor(accent)
    ctx.fill(CGRect(x: box.minX + 7, y: box.maxY - 66, width: w - 14, height: 22))
    // 兩根掛環
    for dx in [-52.0, 52.0] {
        ctx.fill(CGRect(x: cx + dx - 11, y: box.maxY - 22, width: 22, height: 42))
    }
    // 裡面的兩個點
    for dy in [96.0, 152.0] {
        for dx in [-52.0, 4.0] {
            ctx.fill(CGRect(x: cx + dx + 24, y: box.maxY - dy - 26, width: 28, height: 26))
        }
    }
}

// ── 圖示：問號 ──
func helpIcon(cx: Double, top: Double) {
    let d = 214.0
    ctx.setStrokeColor(accent); ctx.setLineWidth(14)
    ctx.strokeEllipse(in: CGRect(x: cx - d/2, y: y(top + d), width: d, height: d))
    text("?", "PingFangTC-Semibold", 132, accent, centerX: cx, baselineFromTop: top + 156)
}

let iconTop = 226.0
let labelBaseline = 618.0
let labels = ["列出行程", "打開行事曆", "使用說明"]

listIcon(cx: cell * 0.5, top: iconTop + 14)
calendarIcon(cx: cell * 1.5, top: iconTop)
helpIcon(cx: cell * 2.5, top: iconTop)

for (i, label) in labels.enumerated() {
    text(label, "PingFangTC-Semibold", 92, ink,
         centerX: cell * (Double(i) + 0.5), baselineFromTop: labelBaseline)
}

guard let image = ctx.makeImage() else { fatalError("image") }
let url = URL(fileURLWithPath: "svra-richmenu.png")
guard let dest = CGImageDestinationCreateWithURL(
        url as CFURL, UTType.png.identifier as CFString, 1, nil) else { fatalError("dest") }
CGImageDestinationAddImage(dest, image, nil)
CGImageDestinationFinalize(dest)
print("done")
