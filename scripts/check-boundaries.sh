#!/usr/bin/env bash
# Enforces speech-to-speech-mobile's dependency direction: the speech layer
# owns audio/VAD/STT/TTS/turn-lifecycle only. It must not own the agent loop,
# concrete LLM/context/tools implementations, or plugin management.
set -euo pipefail

SRC="bindings/android/src/main/java/com/s2s/mobile"
FAIL=0

check() {
  local pattern="$1"
  local label="$2"
  local hits
  hits=$(grep -rn --include="*.kt" -E "$pattern" "$SRC" || true)
  if [ -n "$hits" ]; then
    echo "BOUNDARY VIOLATION: speech-to-speech-mobile imports $label"
    echo "$hits"
    FAIL=1
  fi
}

check '^import com\.s2s\.agent\.' "s2s-agent (the agent loop belongs to the harness, not the speech layer)"
check '^import com\.s2s\.host\.' "s2s-host (plugin composition belongs to the app)"
check '^import com\.s2s\.llm\.' "a concrete LanguageModel implementation"
check '^import com\.s2s\.context\.' "a concrete ContextEngine implementation"
check '^import com\.s2s\.tools\.' "a concrete Tools implementation"

if [ "$FAIL" -ne 0 ]; then
  echo "speech-to-speech-mobile owns audio/VAD/STT/TTS/turn-lifecycle only — model/context/tools/plugins are supplied by the host through interfaces."
  exit 1
fi

echo "Boundary check passed: speech-to-speech-mobile stays within its own layer."
