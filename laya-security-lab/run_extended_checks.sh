#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
trap 'status=$?; printf "%s\n" "$status" > runs/extended.exit' EXIT
export CUDA_VISIBLE_DEVICES=0 TOKENIZERS_PARALLELISM=false OMP_NUM_THREADS=4
.venv/bin/python -u extended_checks.py "$@" > runs/extended.log 2>&1
