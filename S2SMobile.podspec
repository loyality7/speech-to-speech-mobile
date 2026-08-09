Pod::Spec.new do |s|
  s.name             = 'S2SMobile'
  s.version          = '1.0.0'
  s.summary          = '100% On-Device Real-Time Speech-to-Speech Engine for Mobile'
  s.description      = <<-DESC
Real-time on-device speech-to-speech engine featuring VAD, neural STT, streaming LLM inference, and natural TTS with sub-800ms conversational latency.
                       DESC

  s.homepage         = 'https://github.com/loyality7/speech-to-speech-mobile'
  s.license          = { :type => 'Apache-2.0', :file => 'LICENSE' }
  s.author           = { 'loyality7' => 'loyality7@users.noreply.github.com' }
  s.source           = { :git => 'https://github.com/loyality7/speech-to-speech-mobile.git', :tag => s.version.to_s }

  s.ios.deployment_target = '14.0'
  s.swift_version    = '5.7'

  s.source_files = 'core-engine/include/**/*', 'core-engine/src/**/*', 'bindings/ios/S2SMobile/**/*'
  s.public_header_files = 'core-engine/include/**/*.h'

  s.pod_target_xcconfig = {
    'CLANG_CXX_LANGUAGE_STANDARD' => 'c++17',
    'CLANG_CXX_LIBRARY' => 'libc++',
    'HEADER_SEARCH_PATHS' => '"${PODS_TARGET_SRCROOT}/core-engine/include"'
  }
end
