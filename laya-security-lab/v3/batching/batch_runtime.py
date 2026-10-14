"""Batch inference helpers; use existing weights and decision policies unchanged."""
from pathlib import Path
import json,sys
ROOT=Path(__file__).resolve().parent
V3=ROOT.parent
sys.path.insert(0,str(V3))
from common_v3 import LAB,LayaDetector,QwenDetector,sigmoid,sha

MODELS=['laya_v3_normal_relation_sft','qwen3_06b_v3_lora']

def positive_int(value):
    n=int(value)
    if n<1:raise ValueError('Batch size must be positive')
    return n

def load_model(name):
    policy_path=V3/f'models/{name}_decision_policy.json'
    policy=json.loads(policy_path.read_text())
    if name==MODELS[0]:
        path=V3/'models/laya_v3_normal_relation'
        detector=LayaDetector(path);weight=path/'model.safetensors'
    elif name==MODELS[1]:
        path=V3/'models/qwen3_06b_v3_lora'
        detector=QwenDetector(LAB/'v2/models/qwen3-0.6b',adapter=path);weight=path/'adapter_model.safetensors'
    else:raise ValueError(name)
    return detector,policy,{'weights_sha256':sha(weight),'policy_sha256':sha(policy_path)}

def scores(detector,policy,rows):
    return sigmoid(detector.score(rows,batch_size=len(rows))/policy['temperature'])
