#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
trap 'code=$?; printf "%s\n" "$code" > runs/load.exit' EXIT
export CUDA_VISIBLE_DEVICES=0 TOKENIZERS_PARALLELISM=false OMP_NUM_THREADS=4
../.venv/bin/python -u benchmark_load.py > runs/load.log 2>&1
