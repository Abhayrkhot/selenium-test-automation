// Find Print labels in a screenshot using local macOS text recognition.
// Return each centre as x y fractions; Java chooses the nearest candidate.
import Foundation
import Vision
let request = VNRecognizeTextRequest()
request.recognitionLevel = .accurate
// Keep the menu label as read instead of applying spelling corrections.
request.usesLanguageCorrection = false
let handler = VNImageRequestHandler(url: URL(fileURLWithPath: CommandLine.arguments[1]))
try handler.perform([request])
let matches = (request.results ?? []).filter { item in
    guard let text = item.topCandidates(1).first?.string else { return false }
    // Accept Print with either an ellipsis or trailing dots.
    return text.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: ".…"))) == "Print"
}
guard !matches.isEmpty else { fputs("No visible Print text on screen\n", stderr); exit(1) }
for match in matches {
    let box = match.boundingBox
    // Vision starts at the bottom-left; screen coordinates start at the top-left.
    print("\(box.midX) \(1 - box.midY)")
}
