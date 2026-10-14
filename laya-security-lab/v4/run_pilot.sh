#!/usr/bin/env bash
set -uo pipefail
cd /home/keinus/laya-security-lab
printf 'running\n' > v4/runs/pilot_exit_code.txt
.venv/bin/python -u v4/run_experiment.py --experiment lspr_pilot_v2 --epochs 3 --queue
result=$?
printf '%s\n' "$result" > v4/runs/pilot_exit_code.txt
exit "$result"
