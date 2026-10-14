from pathlib import Path
import sys,json,torch
ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'vendor/laya'))
from laya.agent import Agent
from laya.common import collate_items
from model_io import Predictor,encode,collate,forward
from contract import QUESTIONS
torch.set_num_threads(4)
a=Agent(str(ROOT/'models/base'),device='cuda:0',backend='eager')
b=Predictor(str(ROOT/'models/base'),'cuda:0');b.cfg['max_len']=1024
case=next(json.loads(l) for l in (ROOT/'data/processed/test.jsonl').read_text().splitlines() if 'synthetic-episode-00956' in l)
ai=a._encode_state(case['state'],list(QUESTIONS),{k:a._to_internal(q) for k,q in QUESTIONS.items()},1024,192)
bi=encode(case,b.tokenizer,b.cfg)
print('PARAMETERS',a.dtype,a.amp_enabled,next(a.model.parameters()).dtype,next(b.model.parameters()).dtype,a.model.encoder.config.reference_compile,b.model.encoder.config.reference_compile)
print('TOKEN_EQUAL',[(x['ids']==y['ids'],x['markers']==y['markers'],len(x['ids']),len(y['ids'])) for x,y in zip(ai,bi)])
ab=collate_items([ai],a.tok.pad_token_id);bb=collate(bi,b.tokenizer.pad_token_id,'cuda:0')
print('BATCH_EQUAL',[torch.equal(ab[k].to('cuda:0'),bb[i]) for i,k in enumerate(('input_ids','attention_mask','marker_pos','marker_mask','qtype'))])
with torch.inference_mode():
    al=a._forward(ab)[0];bl=forward(b.model,bb,b.device).cpu().numpy();print('DEFAULT_LOGITS',al.tolist(),bl.tolist())
    a.model.encoder.config.reference_compile=b.model.encoder.config.reference_compile
    al=a._forward(ab)[0];print('MATCHED_COMPILE_LOGITS',al.tolist());print('MAX_DELTA',float(abs(al[:,:2]-bl[:,:2]).max()))
