#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
trap 'code=$?; printf "%s\n" "$code" > runs/benchmark.exit' EXIT
export CUDA_VISIBLE_DEVICES=0 TOKENIZERS_PARALLELISM=false OMP_NUM_THREADS=4
../.venv/bin/python -u benchmark_v2.py "$@" > runs/benchmark.log 2>&1
