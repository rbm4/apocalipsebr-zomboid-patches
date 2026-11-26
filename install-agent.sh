#!/usr/bin/env bash
# Install an already-built agent and restart its systemd service.
# Usage: sudo PZ_GAME_JAR=/opt/pzserver/java/projectzomboid.jar bash install-agent.sh [service-name]
# Default game JAR: /opt/pzserver/java/projectzomboid.jar
# Default service: pzextraction.service. Does not change launcher configuration.
set -euo pipefail

git pull

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    echo "Usage: sudo bash install-agent.sh [service-name]"
    echo "Installs target/apocbr-agent.jar into /opt/pzserver/agents/ and restarts the service."
    echo "Default service: pzextraction.service"
    exit 0
fi
if (( $# > 1 )); then
    echo "Usage: sudo bash install-agent.sh [service-name]" >&2
    exit 1
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_JAR="$SCRIPT_DIR/target/apocbr-agent.jar"
DESTINATION_DIR="/opt/pzserver/agents"
SERVICE="${1:-pzextraction.service}"
GAME_JAR="${PZ_GAME_JAR:-/opt/pzserver/java/projectzomboid.jar}"

if (( EUID != 0 )); then
    echo "Run this script with sudo." >&2
    exit 1
fi
if [[ ! -r "$GAME_JAR" || ! -f "$GAME_JAR" ]]; then
    echo "Missing original game JAR: $GAME_JAR" >&2
    echo "Set PZ_GAME_JAR or pass it as an environment variable to this script." >&2
    exit 1
fi

command -v systemctl >/dev/null
command -v install >/dev/null
install -d -m 755 "$DESTINATION_DIR"
cd "$SCRIPT_DIR"
mvn -B -ntp "-Dpz.game.jar=$GAME_JAR" clean package
if [[ ! -r "$SOURCE_JAR" || ! -s "$SOURCE_JAR" ]]; then
    echo "Maven did not produce the agent JAR: $SOURCE_JAR" >&2
    exit 1
fi
echo "Installing agent..."
install -m 644 "$SOURCE_JAR" "$DESTINATION_DIR/apocbr-agent.jar"
echo "Agent installed; $SERVICE start requested."
