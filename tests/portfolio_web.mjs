import assert from 'node:assert/strict';
import {value,plan,movement,upsert,SYMBOLS} from '../site/portfolio.mjs';
const account=()=>({positions:SYMBOLS.map(symbol=>({symbol,quantity:0,average:0,price:100})),cashUsd:1000,cashKrw:10000,capitalKrw:1e6,fx:1300,fee:.001,slippage:.001,reserveUsd:0,priceDate:'2026-10-26',fxDate:'2026-10-26'});
const weights=(cash=0)=>Object.fromEntries([...SYMBOLS,'CASH'].map(s=>[s,s==='CASH'?cash:s==='GLD'?1-cash:0]));
assert.equal(value(account()).totalKrw,1310000);
assert.equal(movement({totalKrw:1250,capitalKrw:1200,flowKrw:200},{totalKrw:1000,capitalKrw:1000,flowKrw:0}),50);
assert.equal(upsert([{date:'2026-10-26',totalKrw:1}],{date:'2026-10-26',totalKrw:2}).length,1);
assert.equal(plan(account(),weights()).rows.find(r=>r.symbol==='GLD').buy,9);
assert.ok(plan(account(),weights(.25)).cashAfter>=250);
assert.equal(plan({...account(),cashKrw:1e9},weights()).budget,1000);
assert.throws(()=>value({...account(),fx:0}));
assert.throws(()=>value({...account(),cashUsd:NaN}));
assert.throws(()=>plan({...account(),positions:[]},weights()));
assert.throws(()=>plan(account(),{GLD:1}));
assert.equal(value({...account(),capitalKnown:false}).profitKrw,null);
assert.equal(movement({totalKrw:1050,capitalKrw:1200,flowKrw:0},{totalKrw:1000,capitalKrw:1000,flowKrw:0}),50);
assert.equal(value({...account(),capitalKrw:0,capitalKnown:true}).profitKrw,1310000);
for(let i=0;i<100;i++){
 const a=account();a.positions=a.positions.map((p,j)=>({...p,quantity:i+j+.4,price:10+(i+j)*3}));
 const p=plan(a,weights(i/100));
 assert.ok(p.cashAfter>=0&&p.rows.every(r=>r.after>=0&&r.sell<=r.current));
 assert.ok(Math.abs(p.rows.reduce((v,r)=>v+r.after*r.price,0)+p.cashAfter+p.estimatedCost-p.budget)<1e-6);
}
console.log('Portfolio web calculations: 13 checks + 100 funding scenarios passed');
