#!/usr/bin/env bash
# Source before ./gradlew if macOS can't find Java:
#   source ./env.sh
if [[ -z "${JAVA_HOME:-}" ]]; then
  if /usr/libexec/java_home -v 21 &>/dev/null; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  elif [[ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  elif [[ -d /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]]; then
    export JAVA_HOME="/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  fi
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
