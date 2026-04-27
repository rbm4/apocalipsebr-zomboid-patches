#!/usr/bin/env bash
# patch.sh - Project Zomboid class patch manager - Linux entrypoint
#
# Interactive launcher for PZ class patches.
#   - Discovers available version folders in this repository
#   - Prompts for the path to projectzomboid.jar
#   - Detects 32 vs 64-bit layout from ProjectZomboid64.json / 32.json
#   - Parses the classpath field to determine the deploy base directory
#   - Verifies (or installs) Java 25+, shared across all patches this session
#   - Lists available patch scripts in the chosen version folder
#   - Runs all patches or a single selected patch
#
# Usage:
#   ./patch.sh
#   ./patch.sh --pz-jar /opt/pzserver/java/projectzomboid.jar
#   ./patch.sh --version 42.17.0 --dry-run
#   ./patch.sh --revert

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REQUIRED_JAVA_MAJOR=25
SHARED_JDK_DIR="$SCRIPT_DIR/jdk"

# -- ANSI colours ---------------------------------------------------------------
RED='\033[0;31m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
WHITE='\033[1;37m'
GRAY='\033[0;90m'
RESET='\033[0m'

# -- Argument parsing -----------------------------------------------------------
PZ_JAR_ARG=""
VERSION_ARG=""
DRY_RUN=false
REVERT=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --pz-jar|-j|--pz-dir|-d)  PZ_JAR_ARG="$2"; shift 2 ;;
        --version|-v) VERSION_ARG="$2"; shift 2 ;;
        --dry-run)    DRY_RUN=true; shift ;;
        --revert)     REVERT=true; shift ;;
        *)
            echo -e "${RED}Unknown option: $1${RESET}" >&2
            echo "Usage: $0 [--pz-jar FILE|DIR] [--version X.Y.Z] [--dry-run] [--revert]" >&2
            exit 1
            ;;
    esac
done

# -- Display helpers ------------------------------------------------------------

SEP="===================================================="

print_header() {
    echo ""
    echo -e "${CYAN}${SEP}${RESET}"
    echo -e "${WHITE}  $1${RESET}"
    echo -e "${CYAN}${SEP}${RESET}"
    echo ""
}

print_divider() {
    echo -e "${GRAY}----------------------------------------------------${RESET}"
}

# -- JDK helpers ----------------------------------------------------------------

get_javac_major() {
    # Returns the major version number of the given javac (e.g. 25), or 0.
    local javac_path="$1"
    "$javac_path" -version 2>&1 | sed -n 's/javac \([0-9]*\).*/\1/p' || echo "0"
}

find_javac() {
    local javac="" ver=0

    # 1. Shared local JDK downloaded by this script
    if [[ -x "$SHARED_JDK_DIR/bin/javac" ]]; then
        ver=$(get_javac_major "$SHARED_JDK_DIR/bin/javac")
        if (( ver >= REQUIRED_JAVA_MAJOR )); then
            echo "$SHARED_JDK_DIR/bin/javac"
            return 0
        fi
    fi

    # 2. PATH
    if command -v javac &>/dev/null; then
        javac=$(command -v javac)
        ver=$(get_javac_major "$javac")
        if (( ver >= REQUIRED_JAVA_MAJOR )); then
            echo "$javac"
            return 0
        fi
    fi

    # 3. Common install locations (amd64 + arm64 + generic)
    local -a search_paths=(
        "/usr/lib/jvm/java-${REQUIRED_JAVA_MAJOR}-openjdk-amd64/bin/javac"
        "/usr/lib/jvm/java-${REQUIRED_JAVA_MAJOR}-openjdk-arm64/bin/javac"
        "/usr/lib/jvm/java-${REQUIRED_JAVA_MAJOR}-openjdk/bin/javac"
        "/usr/lib/jvm/temurin-${REQUIRED_JAVA_MAJOR}.*/bin/javac"
        "/usr/local/lib/jvm/java-${REQUIRED_JAVA_MAJOR}.*/bin/javac"
        "/opt/java/jdk-${REQUIRED_JAVA_MAJOR}.*/bin/javac"
    )
    for pattern in "${search_paths[@]}"; do
        for f in $pattern; do
            if [[ -x "$f" ]]; then
                ver=$(get_javac_major "$f")
                if (( ver >= REQUIRED_JAVA_MAJOR )); then
                    echo "$f"
                    return 0
                fi
            fi
        done
    done

    echo ""
    return 1
}

install_jdk() {
    echo -e "${CYAN}[*] No JDK $REQUIRED_JAVA_MAJOR+ found. Attempting to install...${RESET}"

    # Try system package manager first
    if command -v apt-get &>/dev/null; then
        echo -e "${GRAY}    Trying: apt-get install openjdk-${REQUIRED_JAVA_MAJOR}-jdk-headless${RESET}"
        if apt-get install -y "openjdk-${REQUIRED_JAVA_MAJOR}-jdk-headless" 2>/dev/null; then
            local javac
            javac=$(find_javac || true)
            if [[ -n "$javac" ]]; then
                echo -e "${GREEN}    Installed via apt.${RESET}"
                echo "$javac"
                return 0
            fi
        fi
    fi

    # Fallback: Azul Zulu tar.gz download
    echo -e "${GRAY}    Falling back to Azul Zulu JDK $REQUIRED_JAVA_MAJOR download...${RESET}"

    if ! command -v curl &>/dev/null && ! command -v wget &>/dev/null; then
        echo -e "${RED}ERROR: curl or wget is required to download the JDK.${RESET}" >&2
        echo -e "${YELLOW}       Install manually:  apt install openjdk-${REQUIRED_JAVA_MAJOR}-jdk-headless${RESET}" >&2
        exit 1
    fi

    local api_url="https://api.azul.com/metadata/v1/zulu/packages/?java_version=${REQUIRED_JAVA_MAJOR}&os=linux&arch=x64&archive_type=tar.gz&java_package_type=jdk&latest=true"
    local raw_json download_url

    if command -v curl &>/dev/null; then
        raw_json=$(curl -s "$api_url")
    else
        raw_json=$(wget -qO- "$api_url")
    fi

    download_url=$(python3 -c "
import sys, json
try:
    d = json.loads(sys.stdin.read())
    print(d[0]['download_url'])
except Exception:
    print('')
" <<< "$raw_json")

    if [[ -z "$download_url" ]]; then
        echo -e "${RED}ERROR: Could not retrieve JDK download URL from Azul API.${RESET}" >&2
        echo -e "${YELLOW}       Install manually: https://www.azul.com/downloads/?version=java-${REQUIRED_JAVA_MAJOR}${RESET}" >&2
        exit 1
    fi

    local tar_path="$SCRIPT_DIR/jdk-zulu-download.tar.gz"
    echo -e "${GRAY}    Downloading: $download_url${RESET}"

    if command -v curl &>/dev/null; then
        curl -L -o "$tar_path" "$download_url"
    else
        wget -O "$tar_path" "$download_url"
    fi

    local extract_dir="$SCRIPT_DIR/jdk-zulu-extract"
    rm -rf "$extract_dir"
    mkdir -p "$extract_dir"
    tar -xzf "$tar_path" -C "$extract_dir"

    local inner_dir
    inner_dir=$(find "$extract_dir" -maxdepth 1 -mindepth 1 -type d | head -n 1)
    if [[ -z "$inner_dir" ]]; then
        echo -e "${RED}ERROR: Extracted archive is empty.${RESET}" >&2
        exit 1
    fi

    rm -rf "$SHARED_JDK_DIR"
    mv "$inner_dir" "$SHARED_JDK_DIR"
    rm -f  "$tar_path"
    rm -rf "$extract_dir"

    if [[ ! -x "$SHARED_JDK_DIR/bin/javac" ]]; then
        echo -e "${RED}ERROR: javac not found after JDK install.${RESET}" >&2
        exit 1
    fi

    local ver
    ver=$(get_javac_major "$SHARED_JDK_DIR/bin/javac")
    echo -e "${GREEN}    Installed: javac $ver at $SHARED_JDK_DIR/bin/javac${RESET}"
    echo "$SHARED_JDK_DIR/bin/javac"
}

# -- PZ config helpers ----------------------------------------------------------

parse_classpath_first() {
    # Prints the first entry of the classpath array from the given JSON file.
    local json_file="$1"
    python3 - "$json_file" <<'PYEOF'
import sys, json
try:
    with open(sys.argv[1]) as f:
        d = json.load(f)
    cp = d.get("classpath", [])
    print(cp[0] if cp else "")
except Exception:
    print("")
PYEOF
}

resolve_deploy_base() {
    # classpath[0] starts with "java/" → Linux layout  → deploy under $PZ_DIR/java
    # classpath[0] is "." or bare name → Windows layout → deploy under $PZ_DIR
    local first_cp="$1"
    local pz_dir="$2"
    if [[ "$first_cp" == java/* ]]; then
        echo "$pz_dir/java"
    else
        echo "$pz_dir"
    fi
}

# -- Main -----------------------------------------------------------------------

print_header "Project Zomboid Patch Manager"

# -- 1. Discover version folders -----------------------------------------------

VERSIONS=()
while IFS= read -r line; do
    VERSIONS+=("$line")
done < <(
    for d in "$SCRIPT_DIR"/*/; do
        base=$(basename "$d")
        if [[ "$base" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "$base"
        fi
    done | sort -V
)

if [[ ${#VERSIONS[@]} -eq 0 ]]; then
    echo -e "${RED}ERROR: No version folders (e.g. 42.17.0) found in $SCRIPT_DIR${RESET}" >&2
    exit 1
fi

# -- 2. Version selection ------------------------------------------------------

SEL_VERSION=""

if [[ -n "$VERSION_ARG" ]]; then
    for v in "${VERSIONS[@]}"; do
        if [[ "$v" == "$VERSION_ARG" ]]; then
            SEL_VERSION="$v"
            break
        fi
    done
    if [[ -n "$SEL_VERSION" ]]; then
        echo -e "${GREEN}Version: $SEL_VERSION (pre-selected)${RESET}"
    else
        echo -e "${YELLOW}Version '$VERSION_ARG' not found. Please select from the list.${RESET}"
        echo ""
    fi
fi

if [[ -z "$SEL_VERSION" ]]; then
    echo -e "${CYAN}Available versions:${RESET}"
    for i in "${!VERSIONS[@]}"; do
        echo "  [$((i + 1))] ${VERSIONS[$i]}"
    done
    echo ""

    while [[ -z "$SEL_VERSION" ]]; do
        read -rp "Select version (1-${#VERSIONS[@]}): " choice
        if [[ "$choice" =~ ^[0-9]+$ ]] && (( choice >= 1 && choice <= ${#VERSIONS[@]} )); then
            SEL_VERSION="${VERSIONS[$((choice - 1))]}"
        else
            echo -e "${YELLOW}    Invalid selection. Try again.${RESET}"
        fi
    done
fi

VERSION_DIR="$SCRIPT_DIR/$SEL_VERSION"
echo ""
echo -e "${GRAY}Version directory: $VERSION_DIR${RESET}"

# -- 3. projectzomboid.jar path ------------------------------------------------

echo ""
PZ_JAR=""

while [[ -z "$PZ_JAR" ]]; do
    if [[ -n "$PZ_JAR_ARG" ]]; then
        PZ_JAR="$PZ_JAR_ARG"
        PZ_JAR_ARG=""   # clear so we re-prompt on invalid path
    else
        read -rp "Enter path to projectzomboid.jar: " PZ_JAR
    fi

    # Strip surrounding quotes the user may have typed
    PZ_JAR="${PZ_JAR%\"}"
    PZ_JAR="${PZ_JAR#\"}"
    PZ_JAR="${PZ_JAR%\'}"
    PZ_JAR="${PZ_JAR#\'}"

    # Accept a folder path — look for projectzomboid.jar inside it
    if [[ -d "$PZ_JAR" ]]; then
        candidate="$PZ_JAR/projectzomboid.jar"
        if [[ -f "$candidate" ]]; then
            PZ_JAR="$candidate"
        else
            echo -e "${YELLOW}    projectzomboid.jar not found in folder: $PZ_JAR${RESET}"
            PZ_JAR=""
            continue
        fi
    fi

    if [[ ! -f "$PZ_JAR" ]]; then
        echo -e "${YELLOW}    Path not found: $PZ_JAR${RESET}"
        PZ_JAR=""
    fi
done

PZ_DIR="$(dirname "$(realpath "$PZ_JAR")")"
# On Linux the JAR lives inside a 'java/' subfolder; child scripts expect the
# install root (one level up), not the 'java/' dir itself.
if [[ "$(basename "$PZ_DIR")" == "java" ]]; then
    PZ_DIR="$(dirname "$PZ_DIR")"
fi
echo -e "${GREEN}PZ directory: $PZ_DIR${RESET}"

# -- 4. Read JSON config (arch + classpath) ------------------------------------

JSON_FILE=""
BITS=64
DEPLOY_BASE="$PZ_DIR"

if [[ -f "$PZ_DIR/ProjectZomboid64.json" ]]; then
    JSON_FILE="$PZ_DIR/ProjectZomboid64.json"
    BITS=64
elif [[ -f "$PZ_DIR/ProjectZomboid32.json" ]]; then
    JSON_FILE="$PZ_DIR/ProjectZomboid32.json"
    BITS=32
fi

if [[ -n "$JSON_FILE" ]]; then
    FIRST_CP=$(parse_classpath_first "$JSON_FILE")
    DEPLOY_BASE=$(resolve_deploy_base "$FIRST_CP" "$PZ_DIR")
    echo -e "${GREEN}Architecture: ${BITS}-bit  ($(basename "$JSON_FILE"))${RESET}"
    echo -e "${GRAY}Classpath[0]: $FIRST_CP${RESET}"
    echo -e "${GREEN}Deploy base:  $DEPLOY_BASE${RESET}"
else
    echo -e "${YELLOW}WARNING: ProjectZomboid64.json / ProjectZomboid32.json not found in $PZ_DIR${RESET}"
    echo -e "${YELLOW}         Defaulting to 64-bit, deploy base = PZ directory.${RESET}"
fi

# -- 5. Java compiler check ----------------------------------------------------

echo ""
echo -e "${CYAN}[*] Checking for Java $REQUIRED_JAVA_MAJOR+ compiler...${RESET}"

JAVAC=$(find_javac || true)

if [[ -z "$JAVAC" ]]; then
    JAVAC=$(install_jdk)
else
    ver=$(get_javac_major "$JAVAC")
    echo -e "${GREEN}    Found: javac $ver at $JAVAC${RESET}"
fi

# Expose javac to child scripts via PATH so they find it without re-downloading
JAVAC_BIN_DIR="$(dirname "$JAVAC")"
if [[ ":$PATH:" != *":$JAVAC_BIN_DIR:"* ]]; then
    export PATH="$JAVAC_BIN_DIR:$PATH"
    echo -e "${GRAY}    Added to PATH: $JAVAC_BIN_DIR${RESET}"
fi

# -- 6. Discover patch scripts -------------------------------------------------

PATCHES=()
while IFS= read -r line; do
    PATCHES+=("$line")
done < <(
    for f in "$VERSION_DIR"/patch*.sh; do
        [[ -f "$f" ]] && echo "$f"
    done | sort
)

if [[ ${#PATCHES[@]} -eq 0 ]]; then
    echo ""
    echo -e "${RED}ERROR: No patch scripts (patch*.sh) found in $VERSION_DIR${RESET}" >&2
    exit 1
fi

echo ""
echo -e "${CYAN}Patches available for $SEL_VERSION:${RESET}"
echo "  [0] Run ALL (${#PATCHES[@]} patches)"
for i in "${!PATCHES[@]}"; do
    echo "  [$((i + 1))] $(basename "${PATCHES[$i]}" .sh)"
done
echo ""

# -- 7. Patch selection --------------------------------------------------------

SEL_PATCHES=()

while [[ ${#SEL_PATCHES[@]} -eq 0 ]]; do
    read -rp "Select (0 = all, 1-${#PATCHES[@]} = individual): " choice
    if [[ "$choice" == "0" ]]; then
        SEL_PATCHES=("${PATCHES[@]}")
    elif [[ "$choice" =~ ^[0-9]+$ ]] && (( choice >= 1 && choice <= ${#PATCHES[@]} )); then
        SEL_PATCHES=("${PATCHES[$((choice - 1))]}")
    else
        echo -e "${YELLOW}    Invalid selection. Try again.${RESET}"
    fi
done

# -- 8. Run patches ------------------------------------------------------------

result_file=$(mktemp /tmp/pz-patch-results-XXXXXX.txt)

echo ""
PASSED=0
FAILED=0

for patch in "${SEL_PATCHES[@]}"; do
    echo -en "${CYAN}  Running $(basename "$patch" .sh)...${RESET} "

    chmod +x "$patch"

    call_args=("--pz-dir" "$PZ_DIR")
    [[ "$DRY_RUN" == "true" ]] && call_args+=("--dry-run")
    [[ "$REVERT"  == "true" ]] && call_args+=("--revert")

    {
        echo ""
        echo "----------------------------------------------------"
        echo "Patch: $(basename "$patch" .sh)"
        echo ""
    } >> "$result_file"

    set +e
    bash "$patch" "${call_args[@]}" >> "$result_file" 2>&1
    exit_code=$?
    set -e

    if [[ $exit_code -ne 0 ]]; then
        echo -e "${RED}FAILED${RESET}"
        echo "    FAILED (exit $exit_code)" >> "$result_file"
        FAILED=$((FAILED + 1))
    else
        echo -e "${GREEN}OK${RESET}"
        PASSED=$((PASSED + 1))
    fi
done

# -- Display results -----------------------------------------------------------

echo ""
echo -e "${CYAN}${SEP}${RESET}"
echo -e "${WHITE}  Patch Output${RESET}"
echo -e "${CYAN}${SEP}${RESET}"
echo ""
cat "$result_file"
rm -f "$result_file"

# -- Summary -------------------------------------------------------------------

echo -e "${CYAN}${SEP}${RESET}"
if [[ $FAILED -eq 0 ]]; then
    echo -e "${GREEN}  Session complete - $PASSED passed, $FAILED failed${RESET}"
else
    echo -e "${YELLOW}  Session complete - $PASSED passed, $FAILED failed${RESET}"
fi
echo -e "${CYAN}${SEP}${RESET}"
echo ""
