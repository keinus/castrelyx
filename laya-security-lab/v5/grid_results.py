"""Validate every learning-rate trial and expose the paper's optimistic selection rule."""
import csv, io, json
import numpy as np
from common import ROOT, CONFIGS, SEEDS, metrics, digest, write_json

def collect_grid(data,primary_rows):
    state=json.loads((ROOT/'grid_worker.json').read_text())
    if state['status']!='complete' or len(state['completed'])+len(state['diverged'])!=96:
        raise RuntimeError('Additional learning-rate grid is incomplete')
    rows=[]
    for r in primary_rows:
        if r['model']=='laya':continue
        rows.append(dict(model=r['model'],seed=r['seed'],lr=.001,status='complete',f1=r['f1'],recall=r['recall'],fpr=r['fpr'],
            folder=f'runs/{r["model"]}/seed{r["seed"]}',prediction_sha256=r['prediction_sha256']))
    for lr in [.01,.0005,.0001,.00001]:
        for name in CONFIGS:
            for seed in SEEDS:
                folder=ROOT/f'runs/grid/lr_{lr:g}/{name}/seed{seed}'
                if (folder/'diverged.json').exists():
                    rows.append(dict(model=name,seed=seed,lr=lr,status='numerically_diverged',f1=None,recall=None,fpr=None,
                                     folder=str(folder.relative_to(ROOT)),prediction_sha256=''))
                    continue
                m=json.loads((folder/'training.json').read_text())
                assert m['status']=='complete' and m['lr']==lr
                assert m['source_sha256']==data.meta['source_csv_sha256']
                assert m['schedule_sha256']==data.meta['schedule'][str(seed)]['sha256']
                p=folder/f'predictions_epoch{m["epochs_completed"]}_sequence.npz'
                z=np.load(p);assert np.array_equal(z['row'],data.test) and np.array_equal(z['y'],data.y[data.test])
                score=metrics(z['y'],z['probability'])
                reported=json.loads((folder/m['primary_evaluation']).read_text())['overall']
                for k in score:assert np.isclose(score[k],reported[k],rtol=1e-10,atol=1e-12),(folder,k)
                rows.append(dict(model=name,seed=seed,lr=lr,status='complete',f1=score['f1'],recall=score['recall'],fpr=score['fpr'],
                    folder=str(folder.relative_to(ROOT)),prediction_sha256=digest(p)))
    models={}
    for name in CONFIGS:
        valid=[r for r in rows if r['model']==name and r['status']=='complete']
        groups={}
        for lr in [.01,.001,.0005,.0001,.00001]:
            g=[r for r in valid if r['lr']==lr]
            groups[f'{lr:g}']=dict(valid_repeats=len(g),f1_mean=float(np.mean([r['f1'] for r in g])) if g else None,
                f1_sd=float(np.std([r['f1'] for r in g],ddof=1)) if len(g)>1 else None,
                recall_mean=float(np.mean([r['recall'] for r in g])) if g else None,
                fpr_mean=float(np.mean([r['fpr'] for r in g])) if g else None)
        complete_groups={k:v for k,v in groups.items() if v['valid_repeats']==3}
        best_mean=max(complete_groups,key=lambda k:complete_groups[k]['f1_mean'])
        models[name]=dict(best_repeat=max(valid,key=lambda r:r['f1']),rates=groups,best_mean_lr=best_mean,best_mean=complete_groups[best_mean])
    best_model=max(models,key=lambda n:models[n]['best_mean']['f1_mean'])
    result=dict(trials=120,additional_trials=96,diverged=len(state['diverged']),models=models,best_mean_model=best_model,
        selection='Test-selected learning rate and best repeat, as in paper; optimistic and not independent validation',
        laya_search='One preselected fine-tuning rate, three repeats; no claim of equal tuning effort or globally optimized Laya',rows=rows)
    write_json(ROOT/'runs/grid_comparison.json',result)
    buf=io.StringIO();w=csv.DictWriter(buf,fieldnames=list(rows[0]));w.writeheader();w.writerows(rows)
    (ROOT/'runs/grid_results.csv').write_text(buf.getvalue(),encoding='utf-8-sig')
    return result
