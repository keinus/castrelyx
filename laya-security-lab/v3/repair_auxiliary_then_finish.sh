#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
for i in $(seq 1 3600); do
  if [ -f runs/training.exit ]; then break; fi
  sleep 5
done
test "$(cat runs/training.exit)" = "0"
../.venv/bin/python diagnose_auxiliary.py > runs/auxiliary-quality.log 2>&1
../.venv/bin/python -u train_laya.py --mode normal_relation > runs/laya_normal_relation_train.log 2>&1
bash finish_run.sh
