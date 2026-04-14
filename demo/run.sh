#!/usr/bin/env bash
# Run a single demo config end-to-end. Generated PDFs (and, if a :ksef
# block is present and $KSEF_TEST_TOKEN is set, the .ksef.xml + .upo.xml
# sidecars) are dropped under demo/outputs/<config-name>/ so repeat runs
# don't clobber each other.
#
# Usage:
#   demo/run.sh                       # list configs
#   demo/run.sh non-eu-services       # run one
#   demo/run.sh --all                 # run every config in demo/configs/
#
# The tool writes the PDF to the CURRENT WORKING DIRECTORY based on the
# invoice title. We cd into the per-config output dir before invoking
# clj so everything lands in the right place.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CONFIGS_DIR="$SCRIPT_DIR/configs"
OUTPUTS_DIR="$SCRIPT_DIR/outputs"

list_configs() {
    echo "Available demo configs:"
    for f in "$CONFIGS_DIR"/*.edn; do
        name="$(basename "$f" .edn)"
        printf "  %s\n" "$name"
    done
    echo
    echo "Usage: $0 <config-name>   OR   $0 --all"
}

run_one() {
    local name="$1"
    local cfg="$CONFIGS_DIR/$name.edn"
    if [ ! -f "$cfg" ]; then
        echo "ERROR: config not found: $cfg" >&2
        exit 2
    fi
    local out_dir="$OUTPUTS_DIR/$name"
    rm -rf "$out_dir"
    mkdir -p "$out_dir"
    echo "==> $name"
    echo "    config: $cfg"
    echo "    output: $out_dir"
    (
        cd "$out_dir"
        clj -Sdeps "{:paths [\"$REPO_ROOT/src\" \"$REPO_ROOT/resources\"]}" \
            -M:run "$cfg"
    )
    echo "    artifacts:"
    ls -1 "$out_dir" | sed 's/^/      /'
    echo
}

if [ $# -eq 0 ]; then
    list_configs
    exit 0
fi

if [ "$1" = "--all" ]; then
    for f in "$CONFIGS_DIR"/*.edn; do
        run_one "$(basename "$f" .edn)"
    done
else
    run_one "$1"
fi
