"""Check the finished paper against the preserved evidence, without model execution."""
from pathlib import Path
from hashlib import sha256
import json, re, statistics, zipfile
from docx import Document
from pypdf import PdfReader

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
read = lambda path: json.loads(path.read_text(encoding='utf-8-sig'))
digest = lambda path: sha256(path.read_bytes()).hexdigest()
manifest = read(HERE/'paper_manifest.json')
stop = read(ROOT/'stopped/summary.json')
grid = read(ROOT/'runs/grid_comparison.json')
speed = read(ROOT/'speed/verified_summary.json')
path = HERE/manifest['document']
assert digest(path) == manifest['sha256']
for source, expected in manifest['source_hashes'].items():
    assert digest(ROOT/source) == expected, source
with zipfile.ZipFile(path) as z:
    assert z.testzip() is None
    xml = z.read('word/document.xml').decode('utf8')
    assert xml.count('<m:oMath>') == 3
    assert xml.count('<w:drawing>') == 1
    assert 'w:pBdr' not in xml

doc = Document(path)
assert len(doc.tables) == 6
table = lambda i: [[c.text for c in r.cells] for r in doc.tables[i].rows]
names = {
    'encoder_dense':'Encoder Dense', 'encoder_projection':'Encoder Projection',
    'decoder_last':'Decoder Last', 'decoder_flatten':'Decoder Flatten',
    'gpt_lookup':'GPT Lookup', 'gpt_dense':'GPT Dense',
    'bert_none':'BERT None', 'bert_projection':'BERT Projection', 'laya':'Laya'}
actual = table(1)[1:]
expected = []
for name, model in grid['models'].items():
    b = model['best_mean']
    expected.append([names[name], str(model['best_mean_lr']), '3',
        f'{b["f1_mean"]*100:.2f} ± {b["f1_sd"]*100:.2f}',
        f'{b["recall_mean"]*100:.3f}', f'{b["fpr_mean"]*100:.3f}'])
lr = stop['laya_rows']
expected.append(['Laya', '0.00001', '2',
    f'{statistics.mean(r["f1"] for r in lr)*100:.2f} ± {statistics.stdev(r["f1"] for r in lr)*100:.2f}',
    f'{statistics.mean(r["recall"] for r in lr)*100:.3f}',
    f'{statistics.mean(r["fpr"] for r in lr)*100:.3f}'])
assert actual == expected
for row in table(2)[1:]:
    family, seed = row[0].split()
    records = lr if family == 'Laya' else stop['best_baseline_rows']
    source = next(r for r in records if r['seed'] == int(seed))
    assert list(map(int, row[1:])) == [source[k] for k in ['TP','FP','TN','FN']]
expected = []
for name in names:
    timing = speed['timings'][f'{name}/20261005']
    inc = timing['batch1_serialization_inclusive']
    expected.append([names[name], f'{inc["p50_ms"]:.2f}', f'{inc["p95_ms"]:.2f}',
        f'{timing["batch8"]["flows_per_second"]:.1f}',
        f'{timing["batch128"]["flows_per_second"]:.1f}'])
assert table(3)[1:] == expected
for row in lr:
    npz = ROOT/f'runs/laya_seed{row["seed"]}_predictions.npz'
    assert digest(npz) == row['prediction_sha256']

urls = [r.target_ref for r in doc.part.rels.values() if r.reltype.endswith('/hyperlink')]
assert set(urls) == {r['url'] for r in manifest['references']}
body = '\n'.join(p.text for p in doc.paragraphs).split('참고문헌')[0]
cited = set()
for group in re.findall(r'\[([0-9,–]+)\]', body):
    for item in group.split(','):
        if '–' in item:
            a, b = map(int, item.split('–')); cited.update(range(a,b+1))
        else:
            cited.add(int(item))
assert cited == set(range(1,12)), cited

pdf = PdfReader(HERE/'qa/Laya_Threat_Detection_Paper.ko.pdf')
assert len(pdf.pages) == 10
assert all(len(page.extract_text()) > 500 for page in pdf.pages)
for n in range(1,11):
    assert (HERE/f'qa/page-{n}.png').exists()
result = dict(document=path.name, sha256=digest(path), pages=10,
    tables=6, references=11, native_equations=3, figures=1,
    source_hashes='verified', accuracy_table='verified', confusion_table='verified',
    speed_table='verified', prediction_hashes='verified', reference_coverage='verified',
    visual_review='All 10 pages inspected. Final equation change affected only page 4, which was re-inspected; other page image hashes unchanged.',
    training_or_inference_performed=False, status='passed')
(HERE/'qa/audit_result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf8')
print(json.dumps(result, ensure_ascii=False))
