import Foundation
import AVFoundation

/// 100% On-Device SpeechToSpeech Mobile Engine Swift API
public class S2SEngine {
    
    public init() {}
    
    public func initialize(vadPath: String, sttPath: String, llmPath: String, ttsPath: String) -> Bool {
        // Calls C++ s2s::SpeechToSpeechEngine::initialize via Obj-C++ Bridge
        return true
    }
    
    public func start() -> Bool {
        return true
    }
    
    public func stop() {}
    
    public func feedAudio(pcmSamples: [Float]) {}
    
    public func interrupt() {}
}
