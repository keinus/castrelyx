"""Fail-closed audits of data boundaries and adapters against author semantics."""
import argparse, os, json
import numpy as np
import pandas as pd
from common import ROOT, Data, SEEDS, WINDOW, BATCH, digest, write_json

def main():
    p=argparse.ArgumentParser();p.add_argument('--attention-parity',action='store_true');args=p.parse_args()
    data=Data(); report={}
    assert data.cut==2151248 and len(data.test)==239027
    for seed in SEEDS:
        schedule=data.schedule(seed)
        assert schedule.min()>=WINDOW and schedule.max()<data.cut
        assert np.all(data.y[schedule[:,:,:BATCH//2]]==1)
        assert np.all(data.y[schedule[:,:,BATCH//2:]]==0)
        assert digest(ROOT/f'data/prepared/schedule_{seed}.npy')==data.meta['schedule'][str(seed)]['sha256']
    report['identical_balanced_schedule_and_train_only_context']=True
    # Independent pandas path from the original evaluate() loop, compared to adapter.
    indices=np.r_[data.schedule(SEEDS[0])[0,0][:3],data.test[:3],data.test[-3:]]
    for integers in [False,True]:
        windows=[]
        for target in indices:
            rows=np.arange(target-WINDOW+1,target+1)
            columns={n:data.numeric[rows,j] for j,n in enumerate(data.num_names)}
            for j,n in enumerate(data.cat_names):
                codes=data.categorical[rows,j]
                if integers:columns[n]=codes.astype(np.uint32)
                else:
                    for k in range(1,data.levels[j]+1):columns[f'{n}_{k}']=codes==k
            windows.append(pd.DataFrame(columns))
        joined=pd.concat(windows)
        for name,actual in zip(data.num_names+data.cat_names,data.features(indices,integers)):
            cols=name if integers or name in data.num_names else [c for c in windows[0].columns if c.startswith(name)]
            values=joined[cols].values
            expected=np.array([values[k:k+WINDOW] for k in range(0,len(values),WINDOW)])
            if expected.ndim==2:expected=expected[:,:,None]
            assert np.array_equal(actual,expected),name
    report['streamed_features_match_author_pandas_assembly']=True
    report['all_eight_parameter_counts_match_paper']=all(v['exact'] for v in json.loads((ROOT/'runs/architecture_audit.json').read_text()).values())
    assert report['all_eight_parameter_counts_match_paper']
    # Exact decimal representation check, including range-edge values.
    values=np.asarray(data.numeric[indices]).reshape(-1)
    reconstructed=np.array([format(float(x),'.9g') for x in values],dtype=np.float32)
    assert np.array_equal(values,reconstructed)
    report['numeric_text_roundtrips_float32']=True
    if args.attention_parity:
        import torch
        from laya_adapter import load, collate, forward, Encoder, MAX_LEN
        from common import LAB
        torch.set_num_threads(4)
        # Training inputs only, pre-adaptation weights; the profile is not a model-selection trial.
        train=data.schedule(SEEDS[0])[0,1][::8]
        scores={}
        for attention in ['sdpa','flash_attention_2']:
            os.environ['V5_ATTENTION']=attention
            model,tok,cfg=load(LAB/'models/base','cuda:0',MAX_LEN); model.eval()
            encoder=Encoder(data,tok,cfg);items=encoder.items(train)
            with torch.inference_mode():
                scores[attention]=forward(model,collate(items,tok.pad_token_id,'cuda:0'),'cuda:0').softmax(-1).cpu().numpy()
            del model;torch.cuda.empty_cache()
        diff=float(np.max(np.abs(scores['sdpa']-scores['flash_attention_2'])))
        agreement=float(np.mean(scores['sdpa'].argmax(1)==scores['flash_attention_2'].argmax(1)))
        assert diff<.01 and agreement==1.,(diff,agreement)
        report['attention_parity']=dict(training_cases=len(train),max_abs_probability_difference=diff,argmax_agreement=agreement,tolerance=.01)
    write_json(ROOT/'runs/input_audit.json',report);print(json.dumps(report,indent=2),flush=True)

if __name__=='__main__':main()
