import {timingCard,refreshTimingCards} from './timing.mjs';
const $=id=>document.getElementById(id);
const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const usd=v=>Number(v).toLocaleString('en-US',{style:'currency',currency:'USD'});
const won=v=>Number(v).toLocaleString('ko-KR',{maximumFractionDigits:0})+'원';
const when=v=>v?new Date(v).toLocaleString('ko-KR'):'—';
const labels={draft:'확인 대기',executing:'전송 중',submitted:'접수 · 체결 대기',simulated:'가상 체결',needs_review:'대조 필요',submitting:'전송 중',unknown:'결과 미확인',accepted:'접수',partial:'부분 체결',filled:'체결 완료',closed:'종료',rejected:'거절·경고'};
let state=null,base='',token='',preview=false,busy=false,approvedId=null,resolveId=null;
let statusSyncFailed=false;
function refreshTiming(){refreshTimingCards($('dashboard'),state?.loan_timing,!statusSyncFailed&&state?.loan_timing?.available,state?.loan_timing?.market_date);}
$('server').value=location.port==='8769'?location.origin:'';
function message(text){$('message').textContent=text;}
function rows(p){return p.orders.map(o=>`<div class="plan-row"><div><strong>${esc(o.symbol)}</strong><small>${o.side==='buy'?'매수':'매도'} ${o.quantity}주 · 지정가 ${usd(o.limit)}</small></div><strong>${usd(Number(o.limit)*o.quantity)}</strong></div>`).join('');}
function render(){
 const s=state;
 $('mode-summary').textContent='운용 모드 · '+({observe:'관찰',confirm:'반자동',auto:'자동'}[s?.mode]||'연결 전');
 $('connection').textContent=!s?'연결 전':s.demo?'예시 · 실제 주문 없음':'메리츠 · 개인 서버';
 $('pause').disabled=!s||(busy&&s.paused);
 $('pause').textContent=s?.paused?'신규 주문 재개':'신규 주문 중지';
 $('modes').innerHTML=[['observe','01','관찰','잔고 조회와 주문안 계산'],['confirm','02','반자동','주문안을 확인한 뒤 실행'],['auto','03','자동','검증한 규칙으로 예약 실행']].map(([id,num,name,desc])=>`<button class="card" data-mode="${id}" style="text-align:left;cursor:pointer;border:2px solid ${s?.mode===id?'var(--purple)':'transparent'}" ${!s||busy||(id==='auto'&&!s.auto_available)||(id==='confirm'&&!s.demo&&s.blocked_reasons.length)?'disabled':''}><div class="eyebrow">${num} / ${s?.mode===id?'현재 모드':'운용 모드'}</div><h2>${name}</h2><p class="caption">${desc}</p>${id==='auto'&&!s?.auto_available?'<span class="badge">실주문 검증 후 활성화</span>':''}</button>`).join('');
 if(!s){$('dashboard').innerHTML='<div class="card"><h2>계좌는 연결하고, 실행은 단계적으로.</h2><p class="caption">관찰 → 주문안 확인 → 체결 대조. 모델을 교체해도 자산 이력과 주문 기록은 이어집니다.</p></div>';return;}
 const a=s.account,p=s.plan;
 let total=a?(Number(a.cash_usd)+a.positions.reduce((sum,p)=>sum+Number(p.quantity)*Number(p.price),0))*Number(a.fx)+Number(a.cash_krw):0;
 $('dashboard').innerHTML=`${s.blocked_reasons.length?`<div class="notice">${s.blocked_reasons.map(esc).join(' · ')}</div>`:''}
 <div class="settings-grid"><section class="card" style="background:linear-gradient(120deg,#18213c,#38305e);color:white"><div class="eyebrow" style="color:#bab0ff">${s.demo?'EXAMPLE ACCOUNT':'CONNECTED ACCOUNT'}</div><h2 style="font-size:34px;color:white">${a?won(total):'잔고 동기화 대기'}</h2><p style="color:#bbc2d7">미국 USD 주식 + 원화·달러 현금</p><p>${a?'달러 현금 '+usd(a.cash_usd)+' · 매수가능 '+usd(a.buying_power):'동기화하면 계좌 상태가 표시됩니다.'}</p><p style="font-size:12px;color:#bbc2d7">조회 ${when(a?.as_of)}</p><button class="button" data-call="sync">계좌·체결 동기화</button></section>
 <section class="card"><h2>실행 기준</h2><p class="caption">정규장 지정가 · 매도 체결 후 매수안을 새로 확인합니다. 다음 시가 체결 백테스트와 실행 조건이 다릅니다.</p><div class="cash-line"><span>건별 한도</span><strong>${usd(s.policy.max_order_usd)}</strong></div><div class="cash-line"><span>일일 한도</span><strong>${usd(s.policy.max_daily_usd)}</strong></div><div class="cash-line"><span>최소 현금</span><strong>${usd(s.policy.reserve_usd)}</strong></div><p class="caption">${s.paused?'신규 주문 중지 중 · 기존 접수 주문은 별도 확인':'오래된 계좌·미체결·모델 변경 시 재계산'}</p></section></div>
 <section class="card" style="margin-top:20px"><div class="card-head"><h2>다음 주문안</h2><button class="button primary" data-call="plan">주문안 계산</button></div>${p?`<p class="caption">${esc(p.model_id)} · 신호 ${esc(p.signal_date)} · ${esc(labels[p.state]||p.state)}<br>유효 시각 ${when(p.expires_at)} · ${p.stage==='sell'?'매도 단계':p.stage==='buy'?'매수 단계':'유지'}</p>${rows(p)}${!p.orders.length?'<p>재조정할 주문이 없습니다.</p>':''}<div class="cash-line"><span>전량 체결 가정 현금</span><strong>${usd(p.cash_after_usd)}</strong></div>${p.limits.map(x=>`<p class="negative">${esc(x)}</p>`).join('')}<button class="button primary full" data-review ${s.mode!=='confirm'||s.paused||p.state!=='draft'||!p.orders.length||p.limits.length?'disabled':''}>${s.demo?'가상 체결 내용 확인':'주문 내용 확인'}</button>`:'<p class="caption">최신 계좌와 모델 목표로 계산합니다. 확인 화면에서 종목·수량·가격을 검토할 수 있습니다.</p>'}</section>
 <div class="settings-grid" style="margin-top:20px"><section class="card"><h2>체결 기록</h2>${s.orders.length?s.orders.map(o=>`<div class="plan-row"><div><strong>${esc(o.symbol)} · ${o.side==='buy'?'매수':'매도'} ${o.quantity}주</strong><small>${esc(labels[o.state]||o.state)} · 체결 ${esc(o.filled)}주${o.broker_id?' · 주문 '+esc(o.broker_id):''}</small></div><span>${o.demo?'예시':''}</span></div>`).join(''):'<p class="caption">접수와 실제 체결을 구분해 기록합니다.</p>'}</section><section class="card"><h2>운용 기록</h2>${s.events.map(e=>`<p class="caption"><strong>${esc(e.message)}</strong><br>${when(e.at)}</p>`).join('')||'<p class="caption">설정 변경과 주문 처리 과정이 쌓입니다.</p>'}</section></div>
 <section class="card" style="margin-top:20px"><h2>일별 연결 자산</h2><p class="caption">입출금이 포함된 평가액 기록입니다. 투자 손익으로 해석하지 마세요. 기존 내 자산의 원금·입출금 기록은 별도로 보존됩니다.</p>${s.history.slice(0,14).map(h=>`<div class="cash-line"><span>${esc(h.day)}</span><strong>${won(h.total_krw)}</strong></div>`).join('')||'<p class="caption">동기화하면 하루 한 건씩 저장됩니다.</p>'}</section>`;
 const overview=$('dashboard').querySelector('.settings-grid');
 const criteria=overview.querySelectorAll('section')[1];
 overview.style.gridTemplateColumns='1fr';overview.style.marginBottom='20px';
 overview.after(criteria);criteria.style.marginBottom='20px';
 overview.insertAdjacentHTML('afterend',timingCard(s.loan_timing,!statusSyncFailed&&s.loan_timing?.available,s.loan_timing?.market_date));
 for(const section of $('dashboard').querySelectorAll('section')){
  if(['실행 기준','체결 기록','운용 기록','일별 연결 자산'].includes(section.querySelector('h2')?.textContent)){
   const details=document.createElement('details'),summary=document.createElement('summary');
   summary.textContent=section.querySelector('h2').textContent;details.append(summary);
   while(section.firstChild)details.append(section.firstChild);
   section.append(details);
  }
 }
 if(p?.state==='draft'&&p.orders.length){const action=document.querySelector('[data-review]');action.insertAdjacentHTML('beforebegin','<button class="button full" style="margin-bottom:10px" data-observe>관찰 기록 · 실제 전송 없음</button>');}

 for(const o of s.orders.filter(o=>o.state==='unknown')){
  $('dashboard').insertAdjacentHTML('beforeend',`<section class="card" style="margin-top:20px"><h2>${esc(o.symbol)} · 결과 미확인</h2><p class="caption">자동 재전송을 중단했습니다. 메리츠 주문내역과 대조하세요.</p><button class="button" data-resolve="${esc(o.id)}">주문번호로 대조</button></section>`);
 }
 for(const b of document.querySelectorAll('[data-call],[data-review],[data-observe],[data-resolve]'))b.disabled=b.disabled||busy;
}
async function api(action,body={}){
 if(busy)return;busy=true;render();
 try{
  if(preview){demoAction(action,body);}else{
   if(!base||!token)throw Error('개인 서버를 먼저 연결하세요.');
   const r=await fetch(base+'/api/'+action,{method:action==='status'?'GET':'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},...(action==='status'?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(30000)});
   const data=await r.json();if(!r.ok)throw Error(data.error||'연결 실패');state=data;statusSyncFailed=false;
  }
  if(action==='status'){$('connection-settings').open=false;$('token').value='';}
  message(state.demo?'예시 계좌입니다. 실제 메리츠 주문·잔고에 영향을 주지 않습니다.':state.error||'');
 }catch(e){statusSyncFailed=true;message(e.message+' · 주문 실행 중 오류였다면 재실행 전에 계좌·체결 동기화로 확인하세요.');}
 finally{busy=false;render();}
}
function example(){
 preview=true;approvedId=null;statusSyncFailed=false;
 $('connection-settings').open=false;
 state={demo:true,mode:'observe',paused:false,auto_available:false,blocked_reasons:[],policy:{max_order_usd:500,max_daily_usd:1000,reserve_usd:100},account:{cash_usd:'1428.56',cash_krw:'1200000',buying_power:'1428.56',fx:'1350',as_of:new Date().toISOString(),positions:[{symbol:'TQQQ',quantity:12,price:98.42},{symbol:'SOXL',quantity:25,price:35.74},{symbol:'BITO',quantity:43,price:21.18},{symbol:'GLD',quantity:8,price:255.32}]},plan:null,orders:[],history:[],events:[]};
 message('화면 체험용 예시입니다. 실제 계좌·현재 투자 신호와 무관하며 저장되지 않습니다.');render();
}
function demoAction(action,body){
 const now=new Date().toISOString();
 if(action==='mode'){if(body.mode==='auto')throw Error('자동 모드는 실주문 검증 후 활성화합니다');state.mode=body.mode;}
 if(action==='pause')state.paused=body.paused;
 if(action==='sync'){state.account.as_of=now;}
 if(action==='plan')state.plan={id:crypto.randomUUID(),model_id:'화면 체험용 주문안',signal_date:'예시',created_at:now,expires_at:new Date(Date.now()+300000).toISOString(),stage:'sell',state:'draft',cash_after_usd:'1856.328',limits:[],orders:[{symbol:'SOXL',side:'sell',quantity:12,limit:'35.70'}]};
 if(action==='approve'||action==='simulate'){
  const p=state.plan;
  if(!p||p.id!==body.plan_id||p.state!=='draft'||(action==='approve'&&(state.mode!=='confirm'||state.paused))||Date.now()>Date.parse(p.expires_at))throw Error('주문안을 다시 확인하세요');
  p.state='simulated';state.orders.unshift(...p.orders.map(o=>({...o,demo:true,state:'simulated',filled:o.quantity})));state.events.unshift({at:now,message:'가상 체결 · 실제 잔고 변경 없음'});
 }
 if(action==='mode'||action==='pause')state.events.unshift({at:now,message:action==='mode'?'모드 변경 · '+{observe:'관찰',confirm:'반자동'}[body.mode]:body.paused?'신규 주문 중지':'중지 해제'});
}
$('connect').onsubmit=async e=>{
 e.preventDefault();
 try{
  const u=new URL($('server').value.trim());
  if(u.username||u.password||u.search||u.hash||u.pathname!=='/'||!(u.protocol==='https:'||(u.protocol==='http:'&&['localhost','127.0.0.1'].includes(u.hostname))))throw Error('HTTPS 서버 주소 또는 이 PC의 localhost 주소를 입력하세요');
  if(u.origin!==location.origin)throw Error('보안을 위해 해당 개인 서버 주소를 브라우저에서 연 뒤 연결 토큰을 입력하세요.');
  base=u.origin;token=$('token').value.trim();if(token.length<32)throw Error('연결 토큰은 32자 이상입니다');preview=false;state=null;approvedId=null;await api('status');
 }catch(e){message(e.message);}
};
$('preview').onclick=example;
$('pause').onclick=async()=>{
 if(!state)return;
 if(!busy||preview){await api('pause',{paused:!state.paused});return;}
 try{const r=await fetch(base+'/api/pause',{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:JSON.stringify({paused:true}),signal:AbortSignal.timeout(45000)});const data=await r.json();if(!r.ok)throw Error(data.error);state=data;statusSyncFailed=false;message('신규 주문을 중지했습니다. 접수된 주문은 별도로 확인하세요.');render();}catch(e){statusSyncFailed=true;refreshTiming();message('중지 결과 미확인 · 메리츠에서 접수 주문을 확인하세요.');}
};
$('close-approval').onclick=()=>{$('approval').close();approvedId=null;};
$('send').onclick=async()=>{const id=approvedId;approvedId=null;$('approval').close();if(id)await api('approve',{plan_id:id});};
document.addEventListener('click',async e=>{
 const b=e.target.closest('button');if(!b||b.disabled)return;
 if(b.dataset.mode)await api('mode',{mode:b.dataset.mode});
 if(b.dataset.call)await api(b.dataset.call);
 if(b.hasAttribute('data-observe'))await api('simulate',{plan_id:state.plan.id});
 if(b.dataset.resolve){resolveId=b.dataset.resolve;$('resolve-form').reset();$('resolve').showModal();}
 if(b.hasAttribute('data-review')){approvedId=state.plan.id;$('approval-body').innerHTML=`<p class="caption">${state.demo?'가상 체결 체험입니다. 실제 주문은 전송되지 않습니다.':'이 주문안을 메리츠에 전송합니다. 접수 후 체결 여부를 확인하세요.'}</p>${rows(state.plan)}<p>전량 체결 가정 현금 ${usd(state.plan.cash_after_usd)}</p><p class="caption">정규장 지정가 · 5분 유효 · 매도 후 매수안 별도 확인</p>`;$('send').textContent=state.demo?'가상 체결 기록':'확인한 주문 전송';$('approval').showModal();}
});
$('close-resolve').onclick=()=>{$('resolve').close();resolveId=null;};
$('resolve-form').onsubmit=async e=>{e.preventDefault();const f=new FormData(e.target),id=resolveId;resolveId=null;$('resolve').close();await api('resolve',{order_id:id,broker_id:f.get('broker_id'),broker_date:String(f.get('broker_date')).replaceAll('-','')});};
setInterval(refreshTiming,30000);
window.addEventListener('focus',refreshTiming);
document.addEventListener('visibilitychange',()=>{if(!document.hidden)refreshTiming();});
if(new URLSearchParams(location.search).get('preview')==='1')example();else render();
