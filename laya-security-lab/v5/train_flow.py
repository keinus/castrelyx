"""Run original FlowTransformer components with bounded-memory input assembly.

Model layers are imported unchanged from the pinned authors' implementation.
The adapter preserves upstream balanced batches, loss, threshold and stopping.
"""
import argparse, os, time, json
os.environ.setdefault('TF_CPP_MIN_LOG_LEVEL','2')
os.environ.setdefault('TF_NUM_INTRAOP_THREADS','4')
os.environ.setdefault('TF_NUM_INTEROP_THREADS','2')
import numpy as np
from common import ROOT, Data, CONFIGS, SEEDS, WINDOW, BATCH, STEPS, EPOCHS, PATIENCE, write_json, digest, group_metrics
import tensorflow as tf
for gpu in tf.config.list_physical_devices('GPU'):
    tf.config.experimental.set_memory_growth(gpu,True)
from framework.flow_transformer import FlowTransformer
from framework.flow_transformer_parameters import FlowTransformerParameters
from framework.model_input_specification import ModelInputSpecification
from framework.enumerations import CategoricalFormat
from implementations.pre_processings import StandardPreProcessing
from implementations.input_encodings import NoInputEncoder, RecordLevelEmbed, CategoricalFeatureEmbed, EmbedLayerType
from implementations.classification_heads import LastTokenClassificationHead, FlattenClassificationHead, FeaturewiseEmbedding
from implementations.transformers.basic_transformers import BasicTransformer
from implementations.transformers.named_transformers import GPTSmallTransformer, BERTSmallTransformer

def build(name,data):
    c=CONFIGS[name]
    enc=(NoInputEncoder() if c['encoding']=='none' else
         CategoricalFeatureEmbed(EmbedLayerType.Lookup,c['dim']) if c['encoding']=='lookup' else
         RecordLevelEmbed(c['dim'],project=c['encoding']=='record_projection'))
    seq=(GPTSmallTransformer() if name.startswith('gpt') else BERTSmallTransformer() if name.startswith('bert') else
         BasicTransformer(c['layers'],c['ff'],c['heads'],is_decoder=name.startswith('decoder')))
    head={'last':LastTokenClassificationHead, 'flatten':FlattenClassificationHead,
          'feature_projection':lambda:FeaturewiseEmbedding(project=True)}[c['head']]()
    flow=FlowTransformer(StandardPreProcessing(32),enc,seq,head,FlowTransformerParameters(WINDOW,[128],.1))
    flow.X=True
    flow.model_input_spec=ModelInputSpecification(data.num_names+data.cat_names,len(data.num_names),data.levels,enc.required_input_format)
    return flow.build_model(), c['encoding']=='lookup'

def predictor(model):
    # Same model call as predict(), without re-creating tf.data for every batch.
    @tf.function(reduce_retracing=True)
    def f(xs): return model(xs,training=False)
    def call(xs): return f([tf.convert_to_tensor(x) for x in xs]).numpy().reshape(-1)
    return call

def evaluate(model,data,integers,folder,epoch,context='sequence',batch_size=256):
    fn=predictor(model); scores=[]; t=time.perf_counter()
    for start in range(0,len(data.test),batch_size):
        indices=data.test[start:start+batch_size]
        scores.append(fn(data.features(indices,integers,context)))
        if start and start%(batch_size*100)==0: print(json.dumps(dict(phase='eval',epoch=epoch,rows=start)),flush=True)
    scores=np.concatenate(scores).astype(np.float32)
    np.savez_compressed(folder/f'predictions_epoch{epoch}_{context}.npz',row=data.test,y=data.y[data.test],probability=scores)
    result=group_metrics(data,data.test,scores)
    result.update(epoch=epoch,context=context,seconds=time.perf_counter()-t)
    write_json(folder/f'evaluation_epoch{epoch}_{context}.json',result)
    return result

def train(args):
    data=Data(); name=args.model; seed=args.seed
    group=__import__('pathlib').Path(args.run_group)
    if group.is_absolute() or '..' in group.parts:raise ValueError('Invalid run group')
    folder=ROOT/'runs'/group/name/f'seed{seed}'
    if (folder/'training.json').exists(): raise FileExistsError('Completed run is immutable')
    if folder.exists(): raise FileExistsError('Use explicit resume or a new run; refusing overwrite')
    folder.mkdir(parents=True)
    tf.keras.utils.set_random_seed(seed)
    model,integers=build(name,data)
    # Defaults chosen before test evaluation; winning paper learning rates unavailable.
    model.compile(optimizer=tf.keras.optimizers.Adam(learning_rate=args.lr),loss='binary_crossentropy',metrics=['binary_accuracy'],jit_compile=not args.no_jit)
    model.to_json() # Validate architecture serializability; weights stored separately.
    metadata=dict(model=name,seed=seed,run_group=args.run_group,config=CONFIGS[name],parameter_count=model.count_params(),
        paper_parameter_count=CONFIGS[name]['paper_params'],lr=args.lr,optimizer='Adam',
        max_epochs=EPOCHS,batch_size=BATCH,steps_per_epoch=STEPS,patience=PATIENCE,
        schedule_sha256=digest(ROOT/f'data/prepared/schedule_{seed}.npy'),
        source_sha256=data.meta['source_csv_sha256'],upstream_commit='52236c8c9feccdeb44acf578789db7b8b36bacea',
        tensorflow=tf.__version__,jit_compile=not args.no_jit,devices=[tf.config.experimental.get_device_details(x) for x in tf.config.list_physical_devices('GPU')],
        source_changes='none; adapter streams windows, sorts numerical feature names and exports predictions',
        status='training',history=[])
    write_json(folder/'running.json',metadata)
    schedule=data.schedule(seed); min_loss=100.; stale=0; start=time.perf_counter(); train_seconds=0.; evaluations=[]
    y=np.r_[np.ones(BATCH//2),np.zeros(BATCH//2)].astype(np.float32)
    for epoch in range(EPOCHS):
        reduced=False; losses=[]; epoch_start=time.perf_counter()
        for step,indices in enumerate(schedule[epoch]):
            features=data.features(indices,integers)
            t=time.perf_counter(); r=model.train_on_batch(features,y)
            elapsed=time.perf_counter()-t
            if epoch>0 or step>0: train_seconds+=elapsed
            loss=float(r[0])
            if not np.isfinite(loss): raise FloatingPointError(f'Nonfinite loss {epoch}/{step}')
            losses.append(loss)
            if loss<min_loss: min_loss=loss; reduced=True
        stale=0 if reduced else stale+1
        entry=dict(epoch=epoch+1,mean_loss=float(np.mean(losses)),minimum_batch_loss=min_loss,stale_epochs=stale,seconds=time.perf_counter()-epoch_start)
        metadata['history'].append(entry); write_json(folder/'running.json',metadata)
        print(json.dumps(dict(model=name,seed=seed,**entry)),flush=True)
        stop=stale>PATIENCE
        if epoch==6 or epoch==EPOCHS-1 or stop:
            evaluations.append(evaluate(model,data,integers,folder,epoch+1))
        if stop: break
    model.save_weights(str(folder/'model.weights.h5'))
    (folder/'architecture.json').write_text(model.to_json())
    metadata.update(status='complete',epochs_completed=epoch+1,steps_completed=(epoch+1)*STEPS,
        elapsed_seconds=time.perf_counter()-start,warm_training_seconds=train_seconds,
        warm_training_flows_per_second=(((epoch+1)*STEPS-1)*BATCH)/train_seconds,
        weights_sha256=digest(folder/'model.weights.h5'),evaluation_files=[f'evaluation_epoch{x["epoch"]}_sequence.json' for x in evaluations],
        primary_evaluation=f'evaluation_epoch{epoch+1}_sequence.json')
    write_json(folder/'training.json',metadata)
    print(json.dumps(dict(completed=str(folder),metrics=evaluations[-1]['overall'])),flush=True)

def main():
    p=argparse.ArgumentParser();p.add_argument('--model',choices=CONFIGS);p.add_argument('--seed',type=int,default=SEEDS[0]);p.add_argument('--lr',type=float,default=.001);p.add_argument('--inspect',action='store_true');p.add_argument('--no-jit',action='store_true');p.add_argument('--run-group',default='.')
    args=p.parse_args()
    if args.inspect:
        data=Data(); counts={}
        for name,c in CONFIGS.items():
            tf.keras.backend.clear_session(); m,_=build(name,data)
            counts[name]=dict(observed=m.count_params(),paper=c['paper_params'],exact=m.count_params()==c['paper_params'])
        write_json(ROOT/'runs/architecture_audit.json',counts);print(json.dumps(counts,indent=2))
    else: train(args)
if __name__=='__main__': main()
