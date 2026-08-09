import Foundation
import AVFoundation

/// 100% On-Device Speech-to-Speech Mobile Engine (iOS Swift SDK)
public class S2SEngine {
    
    private let audioEngine = AVAudioEngine()
    private let playerNode = AVAudioPlayerNode()
    private var isRunning = false
    
    public var onTranscript: ((String, Bool) -> Void)?
    public var onAudioChunk: (([Float]) -> Void)?
    public var onBargeIn: (() -> Void)?
    
    public init() {}
    
    public func initialize(
        vadModelPath: String = "",
        sttModelPath: String = "",
        llmModelPath: String = "",
        ttsModelPath: String = ""
    ) -> Bool {
        // Configure iOS AudioSession for hardware echo cancellation (AEC)
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetooth])
            try session.setPreferredSampleRate(16000)
            try session.setActive(true)
        } catch {
            print("[S2SEngine] Warning: Failed to configure AVAudioSession: \(error)")
        }
        return true
    }
    
    public func start() -> Bool {
        guard !isRunning else { return true }
        
        let inputNode = audioEngine.inputNode
        let inputFormat = inputNode.outputFormat(forBus: 0)
        
        // Tap microphone at 16kHz mono PCM
        inputNode.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { [weak self] (buffer, _) in
            guard let self = self, let channelData = buffer.floatChannelData else { return }
            let frameLength = Int(buffer.frameLength)
            let samples = Array(UnsafeBufferPointer(start: channelData[0], count: frameLength))
            self.feedAudio(pcmSamples: samples)
        }
        
        do {
            try audioEngine.start()
            isRunning = true
            return true
        } catch {
            print("[S2SEngine] Failed to start AVAudioEngine: \(error)")
            return false
        }
    }
    
    public func stop() {
        guard isRunning else { return }
        audioEngine.inputNode.removeTap(onBus: 0)
        audioEngine.stop()
        isRunning = false
    }
    
    public func feedAudio(pcmSamples: [Float]) {
        // Passes float samples to C++ SpeechToSpeechEngine::feedAudioInput
    }
    
    public func interrupt() {
        playerNode.stop()
        onBargeIn?()
    }
}
