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
# Why we run clj from REPO_ROOT and move artifacts afterwards:
# pdf/render writes the PDF (and ksef/submit-to-ksef writes sidecars)
# into the CURRENT WORKING DIRECTORY, so the natural move would be to
# cd into the per-config output dir first. That doesn't work: clj needs
# the repo's deps.edn (for the :run alias and the pinned clojure
# version), so it has to run from REPO_ROOT. Instead we snapshot
# pre-existing .pdf/.ksef.xml/.upo.xml files in REPO_ROOT, run clj, and
# move the NEW files into demo/outputs/<config>/ afterwards.

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

# Snapshot filenames matching the given glob in REPO_ROOT. Used to
# distinguish pre-existing artifacts from ones the next clj run produces.
snapshot_artifacts() {
    ( cd "$REPO_ROOT" && ls -1 *.pdf *.ksef.xml *.upo.xml 2>/dev/null || true )
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

    local before after rc
    before="$(snapshot_artifacts)"

    set +e
    ( cd "$REPO_ROOT" && clj -M:run "$cfg" )
    rc=$?
    set -e
    if [ $rc -ne 0 ]; then
        echo "    WARNING: clj exited with $rc (see stack above)" >&2
    fi

    after="$(snapshot_artifacts)"

    # Move files present in `after` but not `before` into out_dir.
    # `comm -13` lists lines only in the second (sorted) stream.
    local new_files
    new_files="$(comm -13 <(printf '%s\n' "$before" | sort -u) <(printf '%s\n' "$after" | sort -u) || true)"
    if [ -z "$new_files" ]; then
        echo "    WARNING: no new artifacts produced" >&2
    else
        while IFS= read -r f; do
            [ -z "$f" ] && continue
            mv "$REPO_ROOT/$f" "$out_dir/"
        done <<< "$new_files"
    fi

    echo "    artifacts:"
    ls -1 "$out_dir" 2>/dev/null | sed 's/^/      /' || echo "      (none)"
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
