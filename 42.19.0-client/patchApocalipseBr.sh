#!/usr/bin/env bash
# patchApocalipseBr.sh
# Client-only patch script for Project Zomboid 42.19.
# Compiles every patched Java source under ./src/zombie and deploys the
# generated .class files as loose classpath overrides next to projectzomboid.jar.
# The JVM loads .class files from the filesystem before looking inside the JAR.
# The original JAR is untouched.
#
# Usage:
#   ./patchApocalipseBr.sh [--pz-dir PATH] [--dry-run] [--revert]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCH_NAME="ApocBR Client FPS Patch (Build 42.19)"
PZ_DIR="/opt/pzserver"
DRY_RUN=false
REVERT=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --pz-dir)    PZ_DIR="$2";  shift 2 ;;
        --dry-run)   DRY_RUN=true; shift ;;
        --revert|-r) REVERT=true;  shift ;;
        *)
            echo "Unknown option: $1" >&2
            echo "Usage: $0 [--pz-dir PATH] [--dry-run] [--revert]" >&2
            exit 1
            ;;
    esac
done

# Auto-detect JAR location (Linux client: PZ root; Linux server: java/ subdir)
if [[ -f "$PZ_DIR/java/projectzomboid.jar" ]]; then
    JAR_FILE="$PZ_DIR/java/projectzomboid.jar"
    DEPLOY_BASE="$PZ_DIR/java"
else
    JAR_FILE="$PZ_DIR/projectzomboid.jar"
    DEPLOY_BASE="$PZ_DIR"
fi

SRC_ROOT="$SCRIPT_DIR/src"
BACKUP_DIR="$SCRIPT_DIR/backups/ApocalipseBrClient"
WORK_DIR="$(mktemp -d /tmp/pzpatch_apocbr_client.XXXXXX)"
trap 'rm -rf "$WORK_DIR"' EXIT
OUTPUT_DIR="$WORK_DIR/classes"

REQUIRED_MAJOR=25

show_client_patch_feature_banner() {
    local source_count="$1"
    local class_count="$2"

    echo ""
    echo "==============================================================================="
    echo "  O QUE ESTE PATCH CLIENTE ENTREGA?"
    echo "==============================================================================="
    echo ""
    echo "Este patch nao muda regras do servidor, loot, dano, spawn ou balanceamento."
    echo "Ele troca classes do cliente para reduzir trabalho repetido que pesa no FPS."
    echo ""
    echo "Nesta execucao foram compilados: $class_count arquivos .class"
    echo "A partir de: $source_count fontes Java."
    echo ""
    echo "Este patch toca em subsistemas que sao estimados custar entre 10%-35% do tempo de um frame"
    echo ""
    echo "Reducao de custo computacional teorica dos subsistemas afetados e estimada em torno de 25%-60%"
    echo ""
    echo "Reducao estimada overall na CPU entra 8%-20%"
    echo ""
    echo "Nao e algo cumulativo, no entanto. Uma reducao de 75% nos vocais de zumbis somada"
    echo "a uma redução de 93% nos veículos ociosos não significa que o frame fique 168% mais rápido."
    echo "Significa que essas fatias diminuem; o ganho final de FPS depende do tamanho que cada fatia tinha anteriormente."
    echo ""
    echo ""
    echo "Principais otimizacoes:"
    echo ""
    echo "  1. Objetos distantes atualizam com menos frequencia - ~10-25% de reducao de processamento" 
    echo "     O jogo passa a simular objetos longe, invisiveis ou em outro andar em ritmo menor."
    echo "     Jogadores, objetos perto da camera e coisas importantes continuam em ritmo alto."
    echo ""
    echo "  2. Zumbis custam menos quando estao parados ou longe"
    echo "     Zumbis mortos, caindo ou em ragdoll continuam completos. Zumbis ativos continuam completos."
    echo "     Zumbis parados podem atualizar menos vezes e a checagem de visao e distribuida entre frames."
    echo "     Em multiplayer, zumbis remotos evitam repetir trabalho que ja vem decidido pela rede."
    echo ""
    echo "  3. Sons/vocais de zumbis sao distribuidos"
    echo "     Em vez de recalcular vocal de todos os zumbis no mesmo frame, o trabalho e dividido em 4 partes."
    echo "     O resultado esperado e menos stutter em hordas grandes."
    echo ""
    echo "  4. Animais parados pesam menos"
    echo "     Animais calmos em idle/zone atualizam bem menos no cliente."
    echo "     Animais andando, atacando, alertas, sendo puxados, presos, em combate ou em veiculo continuam priorizados."
    echo ""
    echo "  5. Veiculos estacionados pesam menos"
    echo "     Carros parados, vazios e sem sirene/alarme/reboque passam para simulacao reduzida."
    echo "     Carros dirigidos, com ocupantes, mecanica aberta, fisica ativa, luzes especiais ou animais continuam completos."
    echo "     Luzes de veiculos parados e distantes tambem sao atualizadas com menos frequencia."
    echo ""
    echo "  6. Renderizacao e GPU fazem menos chamadas repetidas"
    echo "     O patch aumenta buffers de renderizacao e evita reenviar estados, shaders e matrizes iguais."
    echo "     Isso tende a ajudar quando ha muitos modelos, veiculos, zumbis, sombras e efeitos na tela."
    echo ""
    echo "  7. Colisoes e linha de visao/pathfinding ficam mais defensivos"
    echo "     Algumas checagens usam distancia ao quadrado, descartam diferenca de andar cedo e evitam buscas absurdas."
    echo "     Tambem ha protecoes contra loop/travamento em consultas de caminho usadas pelo jogo."
    echo ""
    echo "O que esperar:"
    echo "  - Mais estabilidade de FPS em cidade, horda, muitos carros ou muitos animais."
    echo "  - Menos microtravadas quando o cliente esta sobrecarregado."
    echo "  - Nao e milagre: se a GPU/CPU ja estiver no limite, o ganho depende da cena e das opcoes graficas."
    echo ""
    echo "Este patch e, no maximo, minha melhor tentativa de otimizar, está longe de ser uma solução final, mas é um passo."
    echo "Pressione qualquer tecla para continuar..."
    if [[ -t 0 ]]; then
        IFS= read -r -n 1 _
    else
        IFS= read -r _ || true
    fi
    echo ""
}

echo ""
echo "=== $PATCH_NAME ==="
echo ""

if [[ ! -d "$SRC_ROOT" ]]; then
    echo "ERROR: Source root not found: $SRC_ROOT" >&2
    exit 1
fi

# --- Revert ---
if [[ "$REVERT" == "true" ]]; then
    echo "[*] Reverting client patch class overrides from $DEPLOY_BASE..."
    reverted=false
    while IFS= read -r -d '' src; do
        rel="${src#$SRC_ROOT/}"
        base="${rel%.java}"
        target="$DEPLOY_BASE/$base.class"
        if [[ -f "$target" ]]; then
            rm -f "$target"
            echo "    Removed: $base.class"
            reverted=true
        fi
        for inner in "$DEPLOY_BASE/$base"\$*.class; do
            if [[ -f "$inner" ]]; then
                rm -f "$inner"
                rel_inner="${inner#$DEPLOY_BASE/}"
                echo "    Removed: $rel_inner"
                reverted=true
            fi
        done
    done < <(find "$SRC_ROOT/zombie" -type f -name "*.java" -print0 | sort -z)
    if [[ "$reverted" == false ]]; then
        echo "    No client patch class overrides found."
    fi
    echo ""
    echo "=== Revert complete ==="
    exit 0
fi

if [[ ! -f "$JAR_FILE" ]]; then
    echo "ERROR: projectzomboid.jar not found at $JAR_FILE" >&2
    echo "       Set --pz-dir to your Project Zomboid directory." >&2
    exit 1
fi

# Discover all patched sources
SOURCES=()
while IFS= read -r -d '' file; do
    SOURCES+=("$file")
done < <(find "$SRC_ROOT/zombie" -type f -name "*.java" -print0 | sort -z)

if [[ ${#SOURCES[@]} -eq 0 ]]; then
    echo "ERROR: No patched Java sources found under $SRC_ROOT/zombie" >&2
    exit 1
fi

# Find javac
JAVAC=""
if command -v javac &>/dev/null; then
    ver=$(javac -version 2>&1 | grep -oE '[0-9]+' | head -1 || echo 0)
    if (( ver >= REQUIRED_MAJOR )); then
        JAVAC=$(command -v javac)
    fi
fi
if [[ -z "$JAVAC" ]]; then
    JAVAC="/usr/lib/jvm/java-${REQUIRED_MAJOR}-openjdk-amd64/bin/javac"
fi
if [[ ! -x "$JAVAC" ]]; then
    echo "ERROR: javac >= $REQUIRED_MAJOR not found." >&2
    echo "       Install: apt install openjdk-${REQUIRED_MAJOR}-jdk-headless" >&2
    exit 1
fi

echo "[*] PZ dir:  $PZ_DIR"
echo "[*] JAR:     $JAR_FILE"
echo "[*] Deploy:  $DEPLOY_BASE"
echo "[*] Sources: ${#SOURCES[@]} client Java files"
echo "[*] javac:   $JAVAC ($("$JAVAC" -version 2>&1 | head -1))"
echo ""

# --- Compile ---
mkdir -p "$OUTPUT_DIR"
JAVAC_LOG="$WORK_DIR/javac.log"
echo "[*] Compiling client patched sources..."
if ! "$JAVAC" --release 25 \
    -Xlint:none \
    -implicit:none \
    -cp "$JAR_FILE" \
    -sourcepath "$SRC_ROOT" \
    -d "$OUTPUT_DIR" \
    -encoding UTF-8 \
    "${SOURCES[@]}" >"$JAVAC_LOG" 2>&1; then
    cat "$JAVAC_LOG" >&2
    exit 1
fi
grep -Ev "^(Note:|Recompile with)" "$JAVAC_LOG" || true
echo "    Compiled successfully."

# Discover all compiled classes
CLASSES=()
while IFS= read -r -d '' file; do
    rel="${file#$OUTPUT_DIR/}"
    CLASSES+=("$rel")
done < <(find "$OUTPUT_DIR" -type f -name "*.class" -print0 | sort -z)

if [[ ${#CLASSES[@]} -eq 0 ]]; then
    echo "ERROR: Compilation produced no class files." >&2
    exit 1
fi

# --- Deploy ---
echo ""
if [[ "$DRY_RUN" == "true" ]]; then
    echo "[*] DRY RUN: Would deploy ${#CLASSES[@]} client class files to $DEPLOY_BASE"
    for class_file in "${CLASSES[@]}"; do
        echo "    $class_file"
    done
    echo ""
    echo "=== Dry run complete. No files changed. ==="
else
    echo "[*] Deploying client class overrides..."
    mkdir -p "$BACKUP_DIR"
    ts=$(date +%Y%m%d_%H%M%S)
    deployed=0
    for class_file in "${CLASSES[@]}"; do
        compiled="$OUTPUT_DIR/$class_file"
        target="$DEPLOY_BASE/$class_file"
        target_dir=$(dirname "$target")
        mkdir -p "$target_dir"

        if [[ -f "$target" ]]; then
            safe_name="${class_file//\//_}"
            cp "$target" "$BACKUP_DIR/${safe_name}.prev_$ts" 2>/dev/null || true
        fi

        cp "$compiled" "$target"
        echo "    Deployed: $class_file"
        deployed=$((deployed + 1))
    done
    echo "    Deployed $deployed client class files."
fi

echo ""
echo "=== Done ==="
echo "Patch deployed: $PATCH_NAME"
echo ""
echo "To revert:"
echo "  ./patchApocalipseBr.sh --revert"
echo ""

show_client_patch_feature_banner "${#SOURCES[@]}" "${#CLASSES[@]}"
