"""Frozen Woogong v1 / candidate 3661. No parameter optimizer."""
from pathlib import Path
import json,hashlib
import numpy as np
import pandas as pd
BASE=Path(__file__).resolve().parents[1]
CONFIG=json.loads((BASE/'strategy_parameters.json').read_text())
if hashlib.sha256((BASE/'strategy_parameters.json').read_bytes()).hexdigest()!='5b4e611180cf76bd97fdfe8d273cc5f657db636856faa5f0ed869d7140add5d7':
 raise ValueError('우공 v1 고정 파라미터가 변경됐습니다. 별도 버전으로 검토하세요.')
ASSETS=['TQQQ','BRK.B','GLD','CASH','SOXL','BITO']
def ema(p,n):return p.ewm(span=n,adjust=False,min_periods=n).mean()
def evaluate(p,btc):
 c=CONFIG['core'];q=np.log(p.QQQ).diff();br=np.log(p['BRK.B']).diff()
 beta=(q.rolling(252).cov(br)/br.rolling(252).var()).shift(1).clip(0,2).fillna(0)
 h=1+.175*(beta-1);relative=np.exp((q-h*br).fillna(0).cumsum())
 ef,es=ema(relative,20),ema(relative,45);gap=np.log(ef/es)
 active=False;bull=[]
 for x in gap:
  if np.isfinite(x):
   if x>0:active=True
   elif x<-.005:active=False
  bull.append(active)
 dd=p.QQQ/p.QQQ.rolling(25).max()-1;long=ema(p.QQQ,65)>ema(p.QQQ,252)
 active=False;crash=[]
 for x,trend in zip(dd,long):
  if np.isfinite(x):
   if not active and x<=-.06:active=True
   elif active and x>-.025 and trend:active=False
  crash.append(active)
 ix=p.index[p.index>='2010-02-11'];loc=p.index.get_indexer(ix)
 bull=np.asarray(bull)[loc];crash=np.asarray(crash)[loc];long=long.to_numpy()[loc]
 whip=np.zeros(len(ix),bool);trans=[];lock=0;last=None
 for i in range(1,len(ix)):
  curr=(1 if crash[i] else 0) if bull[i] and long[i] else None
  if curr is not None and last is not None and curr!=last:
   trans=[k for k in trans+[i] if i-k<=12]
   if len(trans)>=2:lock=8;trans=[]
  if lock>0:whip[i]=True;lock-=1
  last=curr
 state=np.full(len(ix),2);state[crash&bull]=3;state[bull&~crash]=0;state[whip]=1
 rgap=(relative/relative.rolling(20).max()-1).reindex(ix).to_numpy();locked=False;count=0
 for i in range(len(ix)):
  if state[i]!=0:locked=False;count=0;continue
  count=count+1 if rgap[i]<=-.03 else 0
  if not locked and count>=1:locked=True
  elif locked and rgap[i]>-.015:locked=False
  if locked:state[i]=4
 # BTC UTC calendar close is shifted a calendar day BEFORE US-day alignment.
 riskp=p[['TQQQ']].copy();riskp['BTC']=btc.shift(1).reindex(p.index);riskp['SOXL']=p.SOXL
 logp=np.log(riskp.where(riskp>0));ret=logp.diff();scores=[]
 for n in [42,252]:scores.append(((logp-logp.shift(n))/(ret.rolling(n,min_periods=n).std(ddof=1).where(lambda x:x>1e-12)*np.sqrt(n))).to_numpy())
 score=sum(scores)/2;weights=np.tile([.3,.5,.2],(len(p),1));ok=np.isfinite(score).all(axis=1)
 x=score[ok];ex=np.exp(x-x.max(axis=1,keepdims=True));weights[ok]=ex/ex.sum(axis=1,keepdims=True);weights=weights[loc]
 d=p.reindex(ix);bp=btc.reindex(ix)
 ready=(d.TQQQ.shift(126).notna()&d.SOXL.shift(126).notna()&bp.shift(127).notna()).to_numpy();weights[~ready]=[1,0,0]
 ratio=p.GLD/p['BRK.B'];df,ds=ema(ratio,84),ema(ratio,378);valid=(df.notna()&ds.notna()).reindex(ix).to_numpy();gold=(df>ds).reindex(ix).to_numpy()
 w=np.zeros((len(ix),6));w[:,3]=1
 for i in range(len(ix)):
  if state[i]==0:w[i]=[weights[i,0],0,0,0,weights[i,2],weights[i,1]]
  elif state[i]==1:w[i]=[0,1,0,0,0,0]
  elif valid[i]:w[i]=[0,0 if gold[i] else 1,1 if gold[i] else 0,0,0,0]
 original=w[:,[0,4,5]].copy();budget=original.sum(axis=1);caps=np.array([1.,.4,1.]);clipped=np.minimum(original,caps)
 for _ in range(4):
  excess=budget-clipped.sum(axis=1);room=np.maximum(caps-clipped,0);score=np.where(room>1e-12,original,0);den=score.sum(axis=1)
  clipped+=np.minimum(excess[:,None]*score/np.maximum(den[:,None],1e-16),room)
 lever=clipped[:,0]+clipped[:,1];shrink=np.minimum(1,.6/np.maximum(lever,1e-16));released=lever*(1-shrink);clipped[:,:2]*=shrink[:,None]
 clipped[:,2]+=np.where(ready,np.minimum(released,np.maximum(caps[2]-clipped[:,2],0)),0.)
 w[:,[0,4,5]]=clipped;w[:,3]+=budget-clipped.sum(axis=1);w[:,3]=1-w[:,[0,1,2,4,5]].sum(axis=1)
 assert np.isfinite(w).all() and w.min()>-1e-12 and np.allclose(w.sum(axis=1),1)
 targets=pd.DataFrame(w,index=ix,columns=ASSETS)
 indicators=pd.DataFrame({'relative':relative,'relative_fast':ef,'relative_slow':es,'gap':gap,'beta':beta,'hedge':h,'qqq_drawdown':dd,'defense_ratio':ratio,'defense_fast':df,'defense_slow':ds},index=p.index).reindex(ix)
 indicators['state']=state;indicators['bull']=bull;indicators['crash']=crash;indicators['whip']=whip
 return targets,indicators

