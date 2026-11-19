#!/usr/bin/env bash
# Install an already-built agent and restart its systemd service.
# Usage: sudo bash install-agent.sh [service-name]
# Default: pzextraction.service. Does not build or change launcher configuration.
set -euo pipefail

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

if (( EUID != 0 )); then
    echo "Run this script with sudo." >&2
    exit 1
fi
if [[ ! -r "$SOURCE_JAR" || ! -s "$SOURCE_JAR" ]]; then
    echo "Missing or unreadable agent JAR: $SOURCE_JAR. Run mvn package first." >&2
    exit 1
fi

command -v systemctl >/dev/null
command -v install >/dev/null
install -d -m 755 "$DESTINATION_DIR"
mvn clean package
echo "Installing agent..."
install -m 644 "$SOURCE_JAR" "$DESTINATION_DIR/apocbr-agent.jar"
echo "Agent installed; $SERVICE start requested."
