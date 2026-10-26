export const SYMBOLS=['TQQQ','SOXL','BITO','BRK.B','GLD'];
const finite=x=>typeof x==='number'&&Number.isFinite(x);
export function validate(a){
 if(!a||!Array.isArray(a.positions)||a.positions.length>30)throw Error('계좌 형식을 확인하세요.');
 for(const k of ['cashUsd','cashKrw','fx','fee','slippage','reserveUsd'])if(!finite(a[k])||a[k]<0)throw Error('현금·환율·비용을 확인하세요.');
 if(!finite(a.capitalKrw)||a.fee>=.1||a.slippage>=.1)throw Error('원금과 비용을 확인하세요.');
 if(!/^\d{4}-\d{2}-\d{2}$/.test(a.priceDate)||!/^\d{4}-\d{2}-\d{2}$/.test(a.fxDate)||!Number.isFinite(Date.parse(a.priceDate))||!Number.isFinite(Date.parse(a.fxDate)))throw Error('기준일은 YYYY-MM-DD 형식입니다.');
 if(new Set(a.positions.map(p=>p.symbol)).size!==a.positions.length)throw Error('종목이 중복되어 있습니다.');
 if(a.positions.some(p=>!(/^[A-Z][A-Z0-9.\-]{0,14}$/.test(p.symbol))||['quantity','average','price'].some(k=>!finite(p[k])||p[k]<0)))throw Error('종목·수량·가격을 확인하세요.');
 return a;
}
export function value(a){
 validate(a);if(a.fx<=0)throw Error('원화 평가를 위한 환율을 입력하세요.');
 if(a.positions.some(p=>p.quantity>0&&p.price<=0))throw Error('보유 종목의 가격을 입력하세요.');
 const stocksUsd=a.positions.reduce((s,p)=>s+p.quantity*p.price,0),totalUsd=stocksUsd+a.cashUsd,totalKrw=totalUsd*a.fx+a.cashKrw;
 if(!finite(totalKrw))throw Error('평가액 범위를 초과했습니다.');
 return {stocksUsd,totalUsd,totalKrw,cashKrw:a.cashUsd*a.fx+a.cashKrw,profitKrw:(a.capitalKnown===true||(a.capitalKnown!==false&&a.capitalKrw>0))?totalKrw-a.capitalKrw:null};
}
export const movement=(current,previous)=>current.totalKrw-previous.totalKrw-((current.flowKrw||0)-(previous.flowKrw||0));
export const upsert=(history,mark)=>[...history.filter(h=>h.date!==mark.date),mark].sort((a,b)=>a.date.localeCompare(b.date));
export function plan(a,weights){
 value(a);const keys=[...SYMBOLS,'CASH'];
 if(Object.keys(weights).sort().join()!==keys.sort().join()||Object.values(weights).some(w=>!finite(w)||w<0)||Math.abs(Object.values(weights).reduce((x,y)=>x+y,0)-1)>1e-8)throw Error('모델 목표 비중을 확인하세요.');
 const ps=Object.fromEntries(a.positions.map(p=>[p.symbol,p]));
 for(const s of SYMBOLS)if(weights[s]>0&&!(ps[s]?.price>0))throw Error(`${s} 기준가격이 필요합니다.`);
 const budget=a.cashUsd+a.positions.filter(p=>SYMBOLS.includes(p.symbol)).reduce((v,p)=>v+p.quantity*p.price,0);
 if(!(budget>0&&budget<1e12))throw Error('달러 운용자산을 입력하세요.');
 const outsideStrategy=a.positions.filter(p=>!SYMBOLS.includes(p.symbol)).reduce((v,p)=>v+p.quantity*p.price,0);
 const desiredCash=Math.min(budget,Math.max(a.reserveUsd,budget*weights.CASH));
 let cash=a.cashUsd,estimatedCost=0;const desired={},buys={},sells={};
 for(const s of SYMBOLS){const p=ps[s]||{quantity:0,price:0};desired[s]=weights[s]>0?Math.floor(budget*weights[s]/p.price):0;sells[s]=Math.floor(Math.max(0,p.quantity-desired[s])+1e-9);const gross=sells[s]*p.price,proceeds=gross*(1-a.slippage)*(1-a.fee);cash+=proceeds;estimatedCost+=gross-proceeds;}
 const priority=[...SYMBOLS].sort((x,y)=>(budget*weights[y]-(ps[y]?.quantity||0)*(ps[y]?.price||0))-(budget*weights[x]-(ps[x]?.quantity||0)*(ps[x]?.price||0)));
 for(const s of priority){const p=ps[s]||{quantity:0,price:0},need=Math.floor(Math.max(0,desired[s]-p.quantity)+1e-9),unit=p.price*(1+a.slippage)*(1+a.fee);buys[s]=need>0&&unit>0?Math.min(need,Math.floor(Math.max(0,cash-desiredCash)/unit+1e-9)):0;cash-=buys[s]*unit;estimatedCost+=buys[s]*(unit-p.price);}
 const rows=SYMBOLS.map(s=>({symbol:s,current:ps[s]?.quantity||0,targetWeight:weights[s],price:ps[s]?.price||0,buy:buys[s]||0,sell:sells[s]||0,after:(ps[s]?.quantity||0)+(buys[s]||0)-(sells[s]||0)}));
 if(cash< -1e-7||rows.some(r=>r.after< -1e-8))throw Error('주문안 자금 검증 실패');
 const bandGap=Math.max(...SYMBOLS.map(s=>Math.abs(weights[s]-(ps[s]?.quantity||0)*(ps[s]?.price||0)/budget)),Math.abs(weights.CASH-a.cashUsd/budget));
 return {rows,budget,cashAfter:Math.max(0,cash),desiredCash,estimatedCost,outsideStrategy,bandGap,fractionalRemainder:a.positions.some(p=>SYMBOLS.includes(p.symbol)&&Math.abs(p.quantity-Math.round(p.quantity))>1e-8)};
}
