const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export function timingAvailable(t,available=false,now=Date.now()){
 const until=Date.parse(t?.valid_until);
 return Boolean(available&&t?.supported&&Number.isFinite(now)&&Number.isFinite(until)&&now<until);
}
export function refreshTimingCards(root,t,available=false,date='—',now=Date.now()){
 const valid=timingAvailable(t,available,now);
 for(const card of root.querySelectorAll('[data-loan-timing]')){
  card.style.borderLeftColor=valid&&t.phase==='entry'?'#7964d9':'#b4b8c8';
  card.querySelector('[data-timing-label]').textContent=valid?t.label:'판정 보류 · 최신 검증 신호 필요';
  card.querySelector('[data-timing-date]').textContent='기준 '+date+(!valid&&t?.label?' · 과거 상태: '+t.label:'');
 }
}
export function timingCard(t,available=false,date='—'){
 const valid=timingAvailable(t,available);
 return `<section class="card" data-loan-timing style="margin-bottom:20px;border-left:4px solid ${valid&&t.phase==='entry'?'#7964d9':'#b4b8c8'}"><div class="eyebrow">LOAN TIMING / RESEARCH</div><div class="card-head"><h2>풀매수 타이밍</h2><span class="badge">연구 신호</span></div><h3 data-timing-label>${esc(valid?t.label:'판정 보류 · 최신 검증 신호 필요')}</h3><p class="caption" data-timing-date>기준 ${esc(date)}${!valid&&t?.label?' · 과거 상태: '+esc(t.label):''}</p><details><summary>신호 조건·차입 유의사항</summary><p>${esc(t?.entry_rule||'방어 → 공격 전환 첫 종가에 진입 조건을 확인합니다.')}</p><p class="caption">${esc(t?.exit_rule||'첫 공격 종료 시 차입분 매도·상환 검토. 완제 전 재차입 금지.')}</p><p>${esc(t?.scope||'마통 한도 전액 사용을 승인하는 신호가 아닙니다.')}</p><p>${esc(t?.checks||'은행 부채·금리·상환 여력 미확인')}</p><p class="caption">${esc(t?.risk||'차입 실행·주문 증액과 연결되지 않은 연구 표시입니다.')}</p><p class="caption">규칙 ${esc(t?.rule_id||'연결 대기')} · 모델 변경 시 다시 검증</p></details></section>`;
}

