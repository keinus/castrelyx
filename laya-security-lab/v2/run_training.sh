#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p runs
trap 'code=$?; printf "%s\n" "$code" > runs/training.exit' EXIT
export CUDA_VISIBLE_DEVICES=0 TOKENIZERS_PARALLELISM=false OMP_NUM_THREADS=4
../.venv/bin/python -u train_laya.py > runs/laya_train.log 2>&1
../.venv/bin/python -u train_qwen.py > runs/qwen_train.log 2>&1
