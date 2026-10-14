#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p runs
trap 'status=$?; printf "%s\n" "$status" > runs/benchmark.exit' EXIT
export CUDA_VISIBLE_DEVICES=0
export TOKENIZERS_PARALLELISM=false
.venv/bin/python -u benchmark.py > runs/benchmark.log 2>&1
.venv/bin/python -u infer.py \
  --zeek data/raw/otrf/datasets/compound/apt29/day1/zeek/individual_zeek_logs/*_conn.log \
  --host data/raw/otrf_historical/evals_apt29_day1_manual_2020-04-29200743.json \
  --assets assets.json --context replay_context.json --limit 0 \
  --output runs/otrf_raw_decisions.jsonl > runs/raw_replay.log 2>&1
