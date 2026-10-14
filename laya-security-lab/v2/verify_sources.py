from pathlib import Path
import json
from audit_v2 import sha
ROOT=Path(__file__).resolve().parent
rows=json.loads((ROOT/'source_weights_manifest.json').read_text())
for row in rows:
    path=ROOT/row['path'];assert path.stat().st_size==row['bytes'];assert sha(path)==row['sha256'],str(path)
print('VERIFIED_ALL_QWEN_SOURCE_WEIGHTS')
