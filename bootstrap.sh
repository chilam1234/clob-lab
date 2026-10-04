#!/usr/bin/env bash
# Bootstrap clob-lab: JDK 21 + Gradle wrapper + smoke test
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

echo "==> Checking Java 21..."
if /usr/libexec/java_home -v 21 &>/dev/null; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
else
  echo "Java 21 not found. Installing via Homebrew (openjdk@21)..."
  if ! command -v brew &>/dev/null; then
    echo "Install Homebrew first: https://brew.sh"
    exit 1
  fi
  brew install openjdk@21
  export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
  if ! grep -q 'openjdk@21' ~/.zprofile 2>/dev/null; then
    echo "Add to ~/.zprofile:"
    echo '  export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"'
  fi
fi

export PATH="$JAVA_HOME/bin:$PATH"
java -version

# Persist JDK path so ./gradlew works in new terminals (machine-local, gitignored)
cat > gradle.properties <<EOF
org.gradle.java.home=${JAVA_HOME}
EOF
echo "Wrote org.gradle.java.home to gradle.properties"

echo ""
echo "==> Gradle wrapper..."
if [[ ! -f gradlew ]]; then
  if command -v gradle &>/dev/null; then
    gradle wrapper --gradle-version 8.10.2
  else
    echo "Installing Gradle via Homebrew..."
    brew install gradle
    gradle wrapper --gradle-version 8.10.2
  fi
fi

echo ""
echo "==> Build + test..."
./gradlew test --console=plain

echo ""
echo "==> Done. Try:"
echo "  source ./env.sh       # if a new terminal can't find Java"
echo "  ./gradlew runDemo    # interactive book demo"
echo "  ./gradlew run        # latency benchmark"
