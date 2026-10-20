// Lingo Lock's helper on the Mac: Apple's voices, speech recognition, the installed apps and which app is
// in front, for the Kotlin app (desktop/, MacHelper.kt). It talks in JSON lines: one command per line on
// standard input, one event per line on standard output. It quits when its input closes (the app quit).
//
// Built by .github/workflows/release.yml with swiftc; Info.plist is embedded in the binary because macOS
// wants usage descriptions before it lets a program ask for the microphone and speech recognition.

import AppKit
import AVFoundation
import Speech

// MARK: - Output

func emit(_ event: [String: Any]) {
    guard var data = try? JSONSerialization.data(withJSONObject: event, options: []) else { return }
    data.append(0x0A)
    FileHandle.standardOutput.write(data)
}

// MARK: - Voices

/** Text-to-speech, reporting when each utterance starts and ends (stories highlight the sentence being read). */
final class Voices: NSObject, AVSpeechSynthesizerDelegate {
    private let synth = AVSpeechSynthesizer()
    private var ids: [ObjectIdentifier: String] = [:]

    override init() {
        super.init()
        synth.delegate = self
    }

    func list() {
        let voices = AVSpeechSynthesisVoice.speechVoices().map { v -> [String: Any] in
            ["id": v.identifier, "name": v.name, "lang": v.language, "quality": v.quality.rawValue]
        }
        emit(["event": "voices", "voices": voices])
    }

    /** [rate] is relative to normal speed (1 = normal), as on the phone. */
    func speak(id: String, text: String, lang: String, rate: Double, queue: Bool) {
        if !queue { synth.stopSpeaking(at: .immediate) }
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = bestVoice(lang)
        let r = Double(AVSpeechUtteranceDefaultSpeechRate) * rate
        utterance.rate = Float(min(max(r, Double(AVSpeechUtteranceMinimumSpeechRate)), Double(AVSpeechUtteranceMaximumSpeechRate)))
        ids[ObjectIdentifier(utterance)] = id
        synth.speak(utterance)
    }

    func stop() {
        synth.stopSpeaking(at: .immediate)
    }

    /** The best installed voice for [lang] ("zh-CN"): premium, then enhanced, then the default one. */
    private func bestVoice(_ lang: String) -> AVSpeechSynthesisVoice? {
        let all = AVSpeechSynthesisVoice.speechVoices()
        let exact = all.filter { $0.language.caseInsensitiveCompare(lang) == .orderedSame }
        let base = lang.split(separator: "-").first.map { String($0).lowercased() } ?? lang.lowercased()
        let candidates = exact.isEmpty ? all.filter { $0.language.lowercased().hasPrefix(base) } : exact
        return candidates.max { $0.quality.rawValue < $1.quality.rawValue } ?? AVSpeechSynthesisVoice(language: lang)
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didStart utterance: AVSpeechUtterance) {
        if let id = ids[ObjectIdentifier(utterance)] { emit(["event": "speak-start", "id": id]) }
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        if let id = ids.removeValue(forKey: ObjectIdentifier(utterance)) { emit(["event": "speak-done", "id": id]) }
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        if let id = ids.removeValue(forKey: ObjectIdentifier(utterance)) { emit(["event": "speak-cancel", "id": id]) }
    }
}

// MARK: - Permissions

func micStatus() -> String {
    switch AVCaptureDevice.authorizationStatus(for: .audio) {
    case .authorized: return "authorized"
    case .denied: return "denied"
    case .restricted: return "restricted"
    default: return "notDetermined"
    }
}

func speechStatus() -> String {
    switch SFSpeechRecognizer.authorizationStatus() {
    case .authorized: return "authorized"
    case .denied: return "denied"
    case .restricted: return "restricted"
    default: return "notDetermined"
    }
}

func reportAuth() {
    emit(["event": "auth", "mic": micStatus(), "speech": speechStatus()])
}

/** Asks for whatever hasn't been decided yet (macOS shows its dialogs once), then calls [done] on the main queue. */
func requestAuth(_ done: @escaping (Bool) -> Void) {
    AVCaptureDevice.requestAccess(for: .audio) { _ in
        SFSpeechRecognizer.requestAuthorization { _ in
            DispatchQueue.main.async { done(micStatus() == "authorized" && speechStatus() == "authorized") }
        }
    }
}

// MARK: - Speech recognition

/**
 * Listens once: partial text while you speak, then up to five guesses. It stops by itself after a short
 * silence once you've said something. Error codes are Android's SpeechRecognizer.ERROR_* numbers.
 */
final class Listener {
    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var timer: Timer?
    private var id = 0
    private var active = false
    private var heardSpeech = false
    private var started = Date()
    private var lastLoud = Date()
    private var lastPartial: String?

    func start(id: Int, lang: String) {
        cancel()
        self.id = id
        active = true
        requestAuth { ok in
            guard self.active, self.id == id else { return }
            guard ok else {
                return self.fail(9, "Allow Lingo Lock to use the microphone and speech recognition: System Settings > Privacy & Security.")
            }
            self.begin(id: id, lang: lang)
        }
    }

    private func begin(id: Int, lang: String) {
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: lang)) else {
            return fail(12, "The Mac has no speech recognition for this language. Type the reading instead.")
        }
        guard recognizer.isAvailable else {
            return fail(2, "Speech recognition isn't available right now (it may need the internet).")
        }
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        self.request = request

        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
            request.append(buffer)
            let db = Listener.decibels(buffer)
            DispatchQueue.main.async { self.onLevel(db, id: id) }
        }
        engine.prepare()
        do {
            try engine.start()
        } catch {
            return fail(3, "Microphone problem: \(error.localizedDescription)")
        }
        task = recognizer.recognitionTask(with: request) { result, error in
            DispatchQueue.main.async { self.onResult(result, error, id: id) }
        }
        started = Date()
        lastLoud = Date()
        heardSpeech = false
        lastPartial = nil
        timer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { _ in self.tick(id: id) }
        emit(["event": "listen-ready", "id": id])
    }

    private func onLevel(_ db: Double, id: Int) {
        guard active, self.id == id else { return }
        if db > -42 {
            heardSpeech = true
            lastLoud = Date()
        }
        // Android reports roughly -2 (quiet) to 10 (loud); the app's mic animation expects that range.
        emit(["event": "level", "id": id, "db": (max(db, -60) + 60) / 5 - 2])
    }

    private func tick(id: Int) {
        guard active, self.id == id else { return }
        let now = Date()
        if !heardSpeech && now.timeIntervalSince(started) > 6 {
            fail(6, "No speech heard. Click the mic and speak.")
        } else if heardSpeech && now.timeIntervalSince(lastLoud) > 1.2 || now.timeIntervalSince(started) > 15 {
            stopAudio()   // the recognizer then sends its final result
        }
    }

    private func onResult(_ result: SFSpeechRecognitionResult?, _ error: Error?, id: Int) {
        guard active, self.id == id else { return }
        if let result = result {
            let best = result.bestTranscription.formattedString
            if result.isFinal {
                var texts = result.transcriptions.prefix(5).map { $0.formattedString }.filter { !$0.isEmpty }
                if texts.isEmpty, !best.isEmpty { texts = [best] }
                finish()
                emit(["event": "result", "id": id, "texts": texts])
                return
            }
            if !best.isEmpty {
                heardSpeech = true
                lastLoud = Date()
                lastPartial = best
                emit(["event": "partial", "id": id, "text": best])
            }
        }
        if error != nil {
            if let partial = lastPartial {
                finish()
                emit(["event": "result", "id": id, "texts": [partial]])
            } else {
                fail(7, "Didn't catch that - try again, a little louder.")
            }
        }
    }

    private func fail(_ code: Int, _ message: String) {
        let id = self.id
        finish()
        emit(["event": "listen-error", "id": id, "code": code, "message": message])
    }

    private func stopAudio() {
        if engine.isRunning {
            engine.stop()
            engine.inputNode.removeTap(onBus: 0)
        }
        request?.endAudio()
    }

    private func finish() {
        active = false
        timer?.invalidate()
        timer = nil
        stopAudio()
        task?.cancel()
        task = nil
        request = nil
    }

    func cancel() {
        if active { finish() }
    }

    private static func decibels(_ buffer: AVAudioPCMBuffer) -> Double {
        guard let data = buffer.floatChannelData, buffer.frameLength > 0 else { return -60 }
        let samples = data[0]
        var sum: Float = 0
        for i in 0..<Int(buffer.frameLength) { sum += samples[i] * samples[i] }
        let rms = sqrt(sum / Float(buffer.frameLength))
        return rms > 0 ? Double(20 * log10(rms)) : -60
    }
}

// MARK: - Apps

/** The apps in the Applications folders, for the list of apps allowed while the practice gate is up. */
func listApps() {
    let fm = FileManager.default
    let dirs = ["/Applications", "/Applications/Utilities", "/System/Applications", "/System/Applications/Utilities",
                NSHomeDirectory() + "/Applications"]
    var seen = Set<String>()
    var apps: [[String: Any]] = []
    for dir in dirs {
        guard let items = try? fm.contentsOfDirectory(atPath: dir) else { continue }
        for item in items where item.hasSuffix(".app") {
            let path = dir + "/" + item
            guard let bundle = Bundle(path: path), let id = bundle.bundleIdentifier, !seen.contains(id) else { continue }
            seen.insert(id)
            let name = (bundle.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String)
                ?? (bundle.object(forInfoDictionaryKey: "CFBundleName") as? String)
                ?? String(item.dropLast(4))
            apps.append(["id": id, "name": name, "path": path])
        }
    }
    apps.sort { ($0["name"] as? String ?? "").localizedCaseInsensitiveCompare($1["name"] as? String ?? "") == .orderedAscending }
    emit(["event": "apps", "apps": apps])
}

func reportFront(_ app: NSRunningApplication?) {
    emit(["event": "front", "id": app?.bundleIdentifier ?? "", "pid": Int(app?.processIdentifier ?? 0)])
}

// MARK: - Commands

let voices = Voices()
let listener = Listener()

func handle(_ c: [String: Any]) {
    guard let cmd = c["cmd"] as? String else { return }
    switch cmd {
    case "voices":
        voices.list()
    case "speak":
        voices.speak(
            id: c["id"] as? String ?? "",
            text: c["text"] as? String ?? "",
            lang: c["lang"] as? String ?? "en-US",
            rate: (c["rate"] as? NSNumber)?.doubleValue ?? 1,
            queue: c["queue"] as? Bool ?? false
        )
    case "stop":
        voices.stop()
    case "listen":
        listener.start(id: (c["id"] as? NSNumber)?.intValue ?? 0, lang: c["lang"] as? String ?? "en-US")
    case "cancel-listen":
        listener.cancel()
    case "auth":
        if c["ask"] as? Bool == true { requestAuth { _ in reportAuth() } } else { reportAuth() }
    case "apps":
        listApps()
    case "front":
        reportFront(NSWorkspace.shared.frontmostApplication)
    default:
        break
    }
}

// MARK: - Main

let app = NSApplication.shared
app.setActivationPolicy(.prohibited)   // no Dock icon, no menu bar

NSWorkspace.shared.notificationCenter.addObserver(
    forName: NSWorkspace.didActivateApplicationNotification, object: nil, queue: .main
) { note in
    reportFront(note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication)
}
reportFront(NSWorkspace.shared.frontmostApplication)

DispatchQueue.global(qos: .userInitiated).async {
    while let line = readLine(strippingNewline: true) {
        guard let data = line.data(using: .utf8),
              let command = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { continue }
        DispatchQueue.main.async { handle(command) }
    }
    DispatchQueue.main.async { exit(0) }   // the app quit
}

app.run()
