// Find a label such as Print or Save in a screenshot using local macOS text recognition.
// Usage: find-text-on-screen.swift <image> <label>
// Return each centre as x y fractions; Java chooses which one to click.
import Foundation
import Vision
let label = CommandLine.arguments.count > 2 ? CommandLine.arguments[2] : "Print"
let request = VNRecognizeTextRequest()
request.recognitionLevel = .accurate
// Keep the label as read instead of applying spelling corrections.
request.usesLanguageCorrection = false
let handler = VNImageRequestHandler(url: URL(fileURLWithPath: CommandLine.arguments[1]))
try handler.perform([request])
let matches = (request.results ?? []).filter { item in
    guard let text = item.topCandidates(1).first?.string else { return false }
    // Accept the label with or without trailing dots, such as Print...
    return text.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: ".…"))) == label
}
guard !matches.isEmpty else { fputs("No visible \(label) text on screen\n", stderr); exit(1) }
for match in matches {
    let box = match.boundingBox
    // Vision starts at the bottom-left; screen coordinates start at the top-left.
    print("\(box.midX) \(1 - box.midY)")
}
