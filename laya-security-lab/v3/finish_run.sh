#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
trap 'code=$?; printf "%s\n" "$code" > runs/finish.exit' EXIT
for i in $(seq 1 3600); do
  if [ -f runs/training.exit ]; then break; fi
  sleep 5
done
test "$(cat runs/training.exit)" = "0"
bash run_benchmark.sh
bash run_load.sh
../.venv/bin/python diagnose_auxiliary.py > runs/auxiliary-quality.log 2>&1
../.venv/bin/python diagnose_data.py > runs/data-diagnostics.log 2>&1
../.venv/bin/python -u benchmark_online.py > runs/online.log 2>&1
../.venv/bin/python smoke_inference.py > runs/cli_smoke.log 2>&1
../.venv/bin/python analyze_results.py > runs/analysis.log 2>&1
../.venv/bin/python write_report.py
../.venv/bin/python capture_runtime.py > runs/runtime.log 2>&1
../.venv/bin/python - <<'PY'
import importlib.metadata as md
from pathlib import Path
versions={d.metadata['Name']:d.version for d in md.distributions() if d.metadata.get('Name')}
Path('runs/requirements-resolved.txt').write_text(''.join(f'{n}=={v}\n' for n,v in sorted(versions.items())))
PY
../.venv/bin/python audit_v3.py > runs/audit.log 2>&1
