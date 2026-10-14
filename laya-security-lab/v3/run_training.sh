#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
trap 'code=$?; printf "%s\n" "$code" > runs/training.exit' EXIT
export CUDA_VISIBLE_DEVICES=0 TOKENIZERS_PARALLELISM=false OMP_NUM_THREADS=4
../.venv/bin/python test_protocol.py > runs/protocol_tests.log 2>&1
../.venv/bin/python -u train_laya.py --mode sft > runs/laya_sft_train.log 2>&1
../.venv/bin/python -u train_laya.py --mode normal_relation > runs/laya_normal_relation_train.log 2>&1
../.venv/bin/python -u train_qwen.py > runs/qwen_train.log 2>&1
