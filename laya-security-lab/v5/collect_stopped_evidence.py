"""Read existing predictions only; never train, infer or resume stopped experiments."""
import json
import time
import zipfile
from pathlib import Path

import numpy as np
from common import ROOT, Data, CONFIGS, metrics, group_metrics, digest, write_json


def aggregate(rows):
    keys = ['f1', 'precision', 'recall', 'fpr', 'accuracy', 'average_precision', 'roc_auc',
            'nonduplicate_f1', 'nonduplicate_recall', 'nonduplicate_fpr']
    return {k: {'mean': float(np.mean([r[k] for r in rows])),
                'sd': float(np.std([r[k] for r in rows], ddof=1)) if len(rows) > 1 else None}
            for k in keys}


def inspect(folder, data, overlap):
    t = json.loads((folder/'training.json').read_text())
    assert t['status'] == 'complete'
    assert t['source_sha256'] == data.meta['source_csv_sha256']
    assert t['schedule_sha256'] == data.meta['schedule'][str(t['seed'])]['sha256']
    path = folder/f'predictions_epoch{t["epochs_completed"]}_sequence.npz'
    z = np.load(path)
    assert np.array_equal(z['row'], data.test)
    assert np.array_equal(z['y'], data.y[data.test])
    score = metrics(z['y'], z['probability'])
    evaluation = json.loads((folder/t['primary_evaluation']).read_text())
    for k, value in score.items():
        assert np.isclose(value, evaluation['overall'][k], rtol=1e-10, atol=1e-12)
    groups = group_metrics(data, data.test, z['probability'])['by_attack']
    assert groups == evaluation['by_attack']
    unique = metrics(z['y'][~overlap], z['probability'][~overlap])
    row = dict(model=t['model'], seed=t['seed'], lr=t['lr'], epochs=t['epochs_completed'],
               parameters=t['parameter_count'], **score,
               nonduplicate_f1=unique['f1'], nonduplicate_recall=unique['recall'], nonduplicate_fpr=unique['fpr'],
               by_attack=groups, elapsed_seconds=t['elapsed_seconds'],
               final_evaluation_seconds=evaluation['seconds'], prediction_sha256=digest(path),
               source_folder=str(folder.relative_to(ROOT)))
    row['checkpoints'] = [dict(file=p.name, epoch=(e:=json.loads(p.read_text()))['epoch'],
                               seconds=e['seconds'], **e['overall'])
                          for p in sorted(folder.glob('evaluation*_sequence.json'))]
    return row


def main():
    data = Data()
    overlap = np.load(ROOT/'data/prepared/test_feature_overlap.npy')
    grid = json.loads((ROOT/'runs/grid_comparison.json').read_text())
    primary = json.loads((ROOT/'runs/verified_progress.json').read_text())
    laya = [inspect(p.parent, data, overlap) for p in sorted((ROOT/'runs/laya').glob('seed*/training.json'))]
    assert len(laya) == 2 and [r['seed'] for r in laya] == [20261005, 20261006]
    selected = {}
    for name, info in grid['models'].items():
        lr = float(info['best_mean_lr'])
        trials = [r for r in grid['rows'] if r['model'] == name and r['lr'] == lr and r['status'] == 'complete']
        rows = [inspect(ROOT/r['folder'], data, overlap) for r in trials]
        assert len(rows) == 3
        selected[name] = dict(lr=lr, rows=rows, aggregate=aggregate(rows),
                              best_repeat=info['best_repeat'], paper=CONFIGS[name])
    best = grid['best_mean_model']
    match = [r for r in selected[best]['rows'] if r['seed'] in {x['seed'] for x in laya}]
    interrupted_folder = ROOT/'runs/laya/seed20261007'
    assert not (interrupted_folder/'training.json').exists()
    assert not list(interrupted_folder.glob('evaluation*.json'))
    interrupted = json.loads((interrupted_folder/'running.json').read_text())
    log = (ROOT/'runs/laya_seed20261007.log').read_text().splitlines()
    last = [json.loads(x) for x in log if x.startswith('{') and '"model": "laya"' in x][-1]
    summary = dict(status='stopped_by_user_report_only', inspected_at=time.time(),
        planned_laya_repeats=3, completed_laya_repeats=2, laya_rows=laya, laya_aggregate=aggregate(laya),
        baseline_trials=grid['trials'], baseline_successes=grid['trials']-grid['diverged'],
        baseline_divergences=[r for r in grid['rows'] if r['status'] != 'complete'],
        best_baseline=best, selected_baselines=selected, matched_baseline_aggregate=aggregate(match),
        matched_pairs=[dict(seed=a['seed'], laya_f1=a['f1'], baseline_f1=b['f1'],
                            delta_f1_pp=(a['f1']-b['f1'])*100, laya_fp=a['FP'], baseline_fp=b['FP'],
                            laya_fn=a['FN'], baseline_fn=b['FN'])
                       for a in laya for b in match if a['seed'] == b['seed']],
        interrupted=dict(seed=20261007, completed_epochs=len(interrupted['history']), last_recorded_step=last,
                         evaluated=False, missing_score_is_not_zero=True),
        timings_present=[str(p.relative_to(ROOT)) for p in (ROOT/'runs').glob('*/seed*/timing.json')],
        primary_fixed_setting_aggregates={name: aggregate([r for r in primary['rows'] if r['model'] == name]) for name in CONFIGS},
        protocol_sha256=digest(ROOT/'protocol.json'), sensitivity_protocol_sha256=digest(ROOT/'sensitivity_protocol.json'),
        source_csv_sha256=data.meta['source_csv_sha256'], test_rows=len(data.test),
        test_feature_overlap=int(overlap.sum()), nonduplicate_test_rows=int((~overlap).sum()),
        evaluation_note='All scores recomputed from saved predictions; no model forward pass or new measurement performed')
    assert not summary['timings_present']
    out = ROOT/'stopped'
    out.mkdir(exist_ok=True)
    write_json(out/'summary.json', summary)
    files = list(ROOT.glob('*.py')) + list(ROOT.glob('requirements-*.txt'))
    files += [ROOT/x for x in ['README.md','protocol.json','sensitivity_protocol.json','execution.json',
                              'runtime_provenance.json','flow_worker.json','laya_worker.json','grid_worker.json',
                              'completion.json','data/prepared/manifest.json','stopped/summary.json']]
    # Preserve original predictions and metadata; omit model weights, optimizer and raw data.
    for p in (ROOT/'runs').rglob('*'):
        if not p.is_file() or 'model' in p.relative_to(ROOT/'runs').parts:
            continue
        if p.suffix in {'.json','.npz','.csv','.log'}:
            files.append(p)
    files += [p for p in (ROOT/'diagnostics').rglob('*') if p.is_file() and p.suffix in {'.json','.log'}]
    files = sorted(set(p for p in files if p.exists()))
    inventory = {str(p.relative_to(ROOT)).replace('\\','/'):digest(p) for p in files}
    write_json(out/'inventory.json',inventory)
    archive=out/'evidence.zip'
    with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED) as z:
        for p in files+[out/'inventory.json']:
            z.write(p,str(p.relative_to(ROOT)).replace('\\','/'))
    receipt=dict(zip_sha256=digest(archive),bytes=archive.stat().st_size,files=len(files),
                 created_at=time.time(),status='stopped_results_frozen')
    write_json(out/'receipt.json',receipt)
    print(json.dumps(dict(receipt=receipt,laya=summary['laya_aggregate'],
                         best=selected[best]['aggregate'],matched=summary['matched_baseline_aggregate'],
                         pairs=summary['matched_pairs'],interrupted=summary['interrupted']),indent=2),flush=True)


if __name__=='__main__':
    main()
