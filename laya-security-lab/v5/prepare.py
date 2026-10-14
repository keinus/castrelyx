"""Full official dataset, train-only upstream preprocessing, deterministic batches."""
import json, sys, time, hashlib, zipfile
import numpy as np
import pandas as pd
from common import ROOT, Data, WINDOW, BATCH, STEPS, EPOCHS, SEEDS, digest, write_json
from framework.dataset_specification import NamedDatasetSpecifications
from framework.enumerations import CategoricalFormat
from implementations.pre_processings import StandardPreProcessing

def main():
    out=ROOT/'data/prepared'
    if (out/'manifest.json').exists(): raise FileExistsError('Prepared dataset already locked')
    out.mkdir(parents=True,exist_ok=True)
    path=ROOT/'data/raw/NF-UNSW-NB15-v2.csv'
    sha=digest(path)
    assert sha=='05019e3ac8d55b3f074f9f042623bbbb1af2527b8b78558c69353ccb2ccefa3f'
    with open(path,'rb') as f: sha1=hashlib.file_digest(f,'sha1').hexdigest()
    assert sha1=='dde9a9e38009ec60121cbb4fd884bf2c7cf5c0b0', 'Publisher BagIt checksum mismatch'
    spec=NamedDatasetSpecifications.unified_flow_format
    raw=pd.read_csv(path, usecols=spec.include_fields+[spec.class_column,'Label'])
    n=len(raw); assert n==2390275
    cut=n-int(n*.10)
    # Upstream uses a Python set: sort it to make the feature order portable.
    numeric=sorted(set(spec.include_fields)-set(spec.categorical_fields))
    categorical=spec.categorical_fields
    p=StandardPreProcessing(32)
    num=np.lib.format.open_memmap(out/'numeric.npy', mode='w+',dtype=np.float32,shape=(n,len(numeric)))
    cat=np.lib.format.open_memmap(out/'categorical.npy',mode='w+',dtype=np.uint8,shape=(n,len(categorical)))
    cleaned={}; parity={}; unseen={}
    for j,name in enumerate(numeric):
        v=raw[name].to_numpy(copy=True)
        bad=~np.isfinite(v) | (v < -1e9) | (v > 1e9)
        cleaned[name]=int(bad.sum()); v[bad]=0; v=v.astype('float32')
        p.fit_numerical(name,v[:cut]); num[:,j]=p.transform_numerical(name,v)
    levels=[]
    for j,name in enumerate(categorical):
        v=raw[name].to_numpy()
        p.fit_categorical(name,v[:cut])
        c=p.transform_categorical(name,v,CategoricalFormat.Integers)
        cat[:,j]=c; levels.append(int(np.unique(c).size))
        unseen[name]=int((~np.isin(v[cut:],p.encoded_levels[name])).sum())
        # Check our streamed one-hot assembly against the original implementation.
        ref=p.transform_categorical(name,v,CategoricalFormat.OneHot)
        assert np.array_equal(np.eye(levels[-1],dtype=bool)[c-1],ref.values)
        parity[name]=True
        del ref
    if not np.isfinite(num).all(): raise ValueError('Nonfinite preprocessed values')
    num.flush(); cat.flush()
    names=sorted(raw.Attack.unique().tolist()); attack=raw.Attack.map({a:i for i,a in enumerate(names)}).to_numpy(np.uint8)
    y=(raw.Attack!='Benign').to_numpy(np.uint8)
    assert np.array_equal(y,raw.Label.to_numpy()), 'Label/Attack mismatch'
    assert int(y.sum())==95053
    np.save(out/'y.npy',y); np.save(out/'attack.npy',attack)
    eligible=np.arange(WINDOW,cut)
    malicious=eligible[y[eligible]==1]; benign=eligible[y[eligible]==0]
    schedule_meta={}
    for seed in SEEDS:
        rs=np.random.RandomState(seed)
        batches=np.empty((EPOCHS,STEPS,BATCH),np.int64)
        for epoch in range(EPOCHS):
            for step in range(STEPS):
                batches[epoch,step]=np.r_[rs.choice(malicious,BATCH//2,replace=False),rs.choice(benign,BATCH//2,replace=False)]
        assert batches.min()>=WINDOW and batches.max()<cut
        np.save(out/f'schedule_{seed}.npy',batches)
        schedule_meta[str(seed)]=dict(sha256=digest(out/f'schedule_{seed}.npy'),unique_targets=len(np.unique(batches)),sample_draws=batches.size)
    meta=dict(source_csv_sha256=sha,publisher_sha1_verified=sha1,n_rows=n,split_row=cut,test_rows=n-cut,
        train_attack=int(y[:cut].sum()), test_attack=int(y[cut:].sum()),
        numeric_fields=numeric,categorical_fields=categorical,levels_per_categorical_feature=levels,
        feature_dimension_onehot=len(numeric)+sum(levels),attack_names=names,
        preprocessing_numeric_min_range={k:[float(x) for x in v] for k,v in p.min_range.items()},
        preprocessing_categorical_levels={k:[float(x) for x in v] for k,v in p.encoded_levels.items()},
        cleaned_numeric_counts=cleaned,unknown_test_categories_mapped_to_one=unseen,onehot_upstream_parity=parity,
        split_attack_counts={s:raw.Attack.iloc[a:b].value_counts().to_dict() for s,a,b in [('train',0,cut),('test',cut,n)]},
        schedule=schedule_meta, window_size=WINDOW, threshold='probability > 0.5',
        ordering='CSV row order; v2 has no flow-start timestamp, not claimed to be chronological',
        numeric_order='sorted instead of upstream hash-randomized set order',
        artifacts={p.name:digest(p) for p in out.glob('*.npy')})
    # Label-independent duplication audit, not a post-hoc change to paper split.
    hashes=pd.util.hash_pandas_object(raw[spec.include_fields],index=False).to_numpy()
    overlap=np.isin(hashes[cut:],np.unique(hashes[:cut]))
    meta['test_feature_hash_present_in_train']=int(overlap.sum())
    np.save(out/'test_feature_overlap.npy',overlap)
    write_json(out/'manifest.json',meta)
    print(json.dumps(meta,indent=2),flush=True)

if __name__=='__main__': main()
