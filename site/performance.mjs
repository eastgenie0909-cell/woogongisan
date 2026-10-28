export const periods={'1m':'1개월','3m':'3개월','1y':'1년',all:'전체'};
export const series=[['model','우공이산','#75dfc5'],['tqqq','TQQQ','#8caaff'],['brkb','버크셔 B','#e5bb70']];
export const percent=v=>(v>=0?'+':'')+(v*100).toFixed(2)+'%';
const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export function windowFor(snapshot,period='1m'){
 const p=snapshot?.performance,w=p?.windows?.[period];
 if(!p?.available||p.version!=='model-tqqq-brkb-v1'||p.currency!=='USD'||p.market_date!==snapshot.market_date||!w||!Array.isArray(w.points)||w.points.length<2)return null;
 if(w.start!==w.points[0].date||w.end!==w.points.at(-1).date||w.end!==snapshot.market_date)return null;
 for(let i=0;i<w.points.length;i++){
  const row=w.points[i];if(!/^\d{4}-\d{2}-\d{2}$/.test(row.date)||(i&&row.date<=w.points[i-1].date))return null;
  if(series.some(([k])=>!Number.isFinite(row[k])||row[k]<-1))return null;
 }
 if(series.some(([k])=>Math.abs(w.points[0][k])>1e-10||!Number.isFinite(w.returns[k])||Math.abs(w.returns[k]-w.points.at(-1)[k])>1e-10))return null;
 return w;
}
export function graph(w,compact=false){
 if(!w)return '<p style="color:#a2b5c9">비교 자료 갱신 대기 · 최신 결과를 확인하세요.</p>';
 const width=720,height=compact?160:270,left=65,top=14,bottom=height-24;
 const values=w.points.flatMap(p=>series.map(([k])=>p[k]));const low=Math.min(0,...values),high=Math.max(0,...values),span=Math.max(.01,high-low);
 const y=v=>top+(bottom-top)*(1-(v-low)/span),x=i=>left+(width-left-10)*i/(w.points.length-1);
 const axes=[low,low+span/2,low+span].map(v=>`<path d="M${left} ${y(v)}H${width}" stroke="#304153" stroke-dasharray="4 6"/><text x="0" y="${y(v)+4}" fill="#a2b5c9" font-size="${compact?19:14}">${(v*100).toFixed(1)}%</text>`).join('');
 return `<svg viewBox="0 0 ${width} ${height}" style="width:100%;display:block" role="img" aria-label="우공이산 모델, TQQQ, 버크셔 B 동일 기간 수익률 비교">${axes}<path d="M${left} ${y(0)}H${width}" stroke="#66778a"/>${series.map(([k,,color],i)=>`<polyline points="${w.points.map((r,i)=>`${x(i)},${y(r[k])}`).join(' ')}" fill="none" stroke="${color}" stroke-width="${i?2.4:3.6}" stroke-linejoin="round" stroke-linecap="round"/>`).join('')}<text x="${left}" y="${height-2}" fill="#a2b5c9" font-size="14">${esc(w.start)}</text><text x="${width}" y="${height-2}" text-anchor="end" fill="#a2b5c9" font-size="14">${esc(w.end)}</text></svg>`;
}
export function card(snapshot,period='1m'){
 const w=windowFor(snapshot,period);
 return `<section class="card" style="background:#142333;color:#dce8f5;margin-bottom:22px"><div class="eyebrow" style="color:#a2b5c9">MODEL PERFORMANCE / USD</div><h2 style="color:white;font-size:27px;margin:8px 0">우공이산 vs 시장</h2><p style="color:#a2b5c9;font-size:12px">모델 · TQQQ · 버크셔 B</p><div style="display:flex;gap:8px;margin:16px 0">${Object.entries(periods).map(([k,v])=>`<button class="button" data-action="performance-period" data-value="${k}" style="background:${period===k?'#30465c':'transparent'};color:${period===k?'#75dfc5':'#a2b5c9'};border-color:#304153">${v}</button>`).join('')}</div><div style="display:flex;gap:14px;justify-content:space-between;margin:18px 0">${series.map(([k,label,color])=>`<div style="flex:1;color:${color}"><div style="font-size:12px">● ${label}</div><strong style="font-size:clamp(16px,4vw,30px)">${w?percent(w.returns[k]):'—'}</strong></div>`).join('')}</div>${graph(w)}<p style="color:#a2b5c9;font-size:11px;line-height:1.8">동일 기간 · 시작 0% · USD · 세전<br>모델 시뮬레이션: 다음 종가·편도0.1% 비용 반영 / 비교종목: 배당·분할 반영 수정종가, 매수 후 보유</p></section>`;
}
