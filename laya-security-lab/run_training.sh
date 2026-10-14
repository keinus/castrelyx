#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p runs
trap 'code=$?; printf "%s\n" "$code" > runs/train.exit' EXIT
export CUDA_VISIBLE_DEVICES=0 TOKENIZERS_PARALLELISM=false OMP_NUM_THREADS=4 PYTHONUNBUFFERED=1
.venv/bin/python test_pipeline.py > runs/tests.log 2>&1
.venv/bin/python preflight.py > runs/preflight.log 2>&1
.venv/bin/python train.py --epochs 3 --micro-batch 4 --grad-accum 8 --max-len 1024 > runs/train.log 2>&1
