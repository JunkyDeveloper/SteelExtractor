#!/usr/bin/env bash
# Seed extraction service launcher.
#
# For each --seed: wipes run/world, sets level-seed in run/server.properties, starts the
# headless Fabric server with the extractor mod, and waits for it to exit. Each job writes
#   <out>/<seed>/steel-core/test_assets/chunk_stage_hashes.json
#   <out>/<seed>/steel-core/test_assets/biome_hashes.json
#
# Usage:
#   scripts/extract.sh --seed 13579 [--seed 42 ...] --area minX,minZ,maxX,maxZ \
#                      [--tile 10] [--dims minecraft:overworld,minecraft:the_nether] [--out output]
#
# --area is in chunk coordinates, inclusive. Jobs run sequentially (they share run/).
set -euo pipefail

usage() { sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-1}"; }

seeds=()
area=""
tile=10
dims=""
out="output"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --seed) seeds+=("$2"); shift 2 ;;
        --area) area="$2"; shift 2 ;;
        --tile) tile="$2"; shift 2 ;;
        --dims) dims="$2"; shift 2 ;;
        --out)  out="$2"; shift 2 ;;
        -h|--help) usage 0 ;;
        *) echo "Unknown argument: $1" >&2; usage ;;
    esac
done

[[ ${#seeds[@]} -gt 0 ]] || { echo "At least one --seed is required" >&2; usage; }
[[ -n "$area" ]] || { echo "--area is required" >&2; usage; }
for s in "${seeds[@]}"; do
    [[ "$s" =~ ^-?[0-9]+$ ]] || { echo "Seed must be an integer: $s" >&2; exit 1; }
done

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_dir="$project_dir/run"
mkdir -p "$run_dir" "$out"
out_abs="$(cd "$out" && pwd)"

# Minecraft needs the EULA accepted to start the server.
echo "eula=true" > "$run_dir/eula.txt"

set_property() {
    local file="$1" key="$2" value="$3"
    touch "$file"
    if grep -q "^${key}=" "$file"; then
        sed -i "s|^${key}=.*|${key}=${value}|" "$file"
    else
        echo "${key}=${value}" >> "$file"
    fi
}

for seed in "${seeds[@]}"; do
    echo "=== Extracting seed $seed (area $area, tile $tile${dims:+, dims $dims}) ==="

    # Fresh world every job: chunks loaded from disk would skip generation and the TERRAIN hash.
    rm -rf "$run_dir/world"
    set_property "$run_dir/server.properties" level-seed "$seed"
    set_property "$run_dir/server.properties" level-name world

    job_out="$out_abs/$seed"
    mkdir -p "$job_out"

    set +e
    STEEL_EXTRACTOR_AREA="$area" \
    STEEL_EXTRACTOR_TILE_SIZE="$tile" \
    STEEL_EXTRACTOR_DIMENSIONS="$dims" \
    STEEL_EXTRACTOR_OUTPUT_DIRECTORY="$job_out" \
    STEEL_EXTRACTOR_EXIT_ON_COMPLETE=1 \
        "$project_dir/gradlew" -p "$project_dir" runServer --args=nogui 2>&1 | tee "$job_out/server.log"

    status=${PIPESTATUS[0]}
    set -e
    hashes="$job_out/steel-core/test_assets/chunk_stage_hashes.json"
    if [[ $status -ne 0 ]] || ! grep -q "All extractors complete" "$job_out/server.log"; then
        echo "Seed $seed failed (exit $status), see $job_out/server.log" >&2
        exit 1
    fi

    echo "Seed $seed done:"
    echo "  $hashes"
    echo "  $job_out/steel-core/test_assets/biome_hashes.json"
done
