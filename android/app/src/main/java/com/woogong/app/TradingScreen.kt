package com.woogong.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant

private fun stateLabel(s:String)=mapOf("draft" to "확인 대기","executing" to "전송 중","submitted" to "접수·체결 대기","simulated" to "가상 체결","needs_review" to "대조 필요","unknown" to "결과 미확인","submitting" to "전송 중","accepted" to "접수","partial" to "부분 체결","filled" to "체결 완료","closed" to "종료")[s]?:s

@Composable fun TradingScreen(){
 val c=LocalContext.current;val scope=rememberCoroutineScope()
 var url by remember{mutableStateOf(TradingConnection.url(c))};var token by remember{mutableStateOf("")}
 var state by remember{mutableStateOf<JSONObject?>(null)};var busy by remember{mutableStateOf(false)}
 var settings by remember{mutableStateOf(url.isEmpty())};var modeSettings by remember{mutableStateOf(false)};var records by remember{mutableStateOf(false)}
 var message by remember{mutableStateOf("")};var review by remember{mutableStateOf<JSONObject?>(null)}
 var resolving by remember{mutableStateOf<String?>(null)};var brokerId by remember{mutableStateOf("")};var brokerDate by remember{mutableStateOf("")}
 fun call(action:String,body:JSONObject=JSONObject()){
  if(busy)return
  scope.launch{busy=true;try{state=withContext(Dispatchers.IO){TradingConnection.call(c,action,body)};if(action=="status")settings=false;message=if(state!!.optBoolean("demo"))"예시 계좌 · 실제 주문과 잔고에 영향을 주지 않습니다" else ""}catch(e:Exception){message=(e.message?:"연결 실패")+"\n주문 실행 중 오류라면 재실행 전 계좌·체결을 동기화하세요."}finally{busy=false}}
 }
 LaunchedEffect(Unit){if(url.isNotEmpty())call("status")}
 Text("운용 센터",fontSize=25.sp,fontWeight=FontWeight.Bold)
 Text("계좌와 원칙을 연결하고, 주문 과정을 기록합니다.")
 if(message.isNotEmpty())Panel{Text(message,color=Mint)}
 TextButton(onClick={settings=!settings}){Text(if(settings)"연결 설정 접기" else "연결 설정")}
 if(settings)Panel{
  Text("개인 서버 연결",fontWeight=FontWeight.Bold)
  Text("메리츠 인증키는 서버에서 보관합니다. 여기는 서버 주소와 연결 토큰을 입력하세요.",fontSize=12.sp)
  OutlinedTextField(url,{url=it},label={Text("https://개인서버")},singleLine=true,modifier=Modifier.fillMaxWidth())
  OutlinedTextField(token,{token=it},label={Text("서버 연결 토큰")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
   Button(enabled=!busy,onClick={try{TradingConnection.save(c,url,token.trim());token="";state=null;call("status")}catch(e:Exception){message=e.message?:"설정을 확인하세요"}}){Text("저장하고 연결")}
   TextButton(enabled=!busy,onClick={TradingConnection.clear(c);state=null;url="";token="";message="기기 연결 정보를 삭제했습니다. 서버의 운용 상태는 별도로 확인하세요."}){Text("연결 정보 삭제")}
  }
 }
 val s=state
 if(s==null){Panel{Text("관찰 → 반자동 → 자동",fontWeight=FontWeight.Bold);Text("관찰 모드로 계좌와 주문안을 먼저 확인합니다. 반자동은 확인한 주문만 실행하고, 자동 모드는 실주문 검증 후 활성화합니다.")};return}
 val demo=s.optBoolean("demo");val paused=s.optBoolean("paused");val mode=s.optString("mode","observe")
 val blocked=s.getJSONArray("blocked_reasons")
 Panel{
  Text(if(demo)"예시 서버 · 실제 주문 없음" else "메리츠 연결 운용",fontWeight=FontWeight.Bold)
  TextButton(onClick={modeSettings=!modeSettings}){Text("운용 모드 · "+(mapOf("observe" to "관찰","confirm" to "반자동","auto" to "자동")[mode]?:mode)+" · 변경")}
  if(modeSettings)Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){
   listOf("observe" to "관찰","confirm" to "반자동","auto" to "자동").forEach{(id,label)->FilterChip(selected=mode==id,enabled=!busy&&(id=="observe"||(id=="confirm"&&(demo||blocked.length()==0))||(id=="auto"&&s.optBoolean("auto_available"))),onClick={call("mode",JSONObject().put("mode",id))},label={Text(label)})}
  }
  if(modeSettings)for(i in 0 until blocked.length())Text(blocked.getString(i),fontSize=12.sp,color=Gold)
  if(modeSettings&&!s.optBoolean("auto_available"))Text("자동 모드: 실행 검증과 서버 활성화 후 사용",fontSize=12.sp)
  Button(enabled=!busy||!paused,onClick={
   if(busy&&!paused)scope.launch{try{state=withContext(Dispatchers.IO){TradingConnection.call(c,"pause",JSONObject().put("paused",true))};message="신규 주문을 중지했습니다. 접수 주문은 별도로 확인하세요."}catch(_:Exception){message="중지 결과 미확인 · 메리츠에서 주문 상태를 확인하세요."}}
   else call("pause",JSONObject().put("paused",!paused))
  },modifier=Modifier.fillMaxWidth()){Text(if(paused)"신규 주문 재개" else "신규 주문 중지")}
  Text("중지는 신규 전송을 막습니다. 이미 접수된 주문은 메리츠에서 별도로 확인하세요.",fontSize=11.sp)
 }
 val a=s.optJSONObject("account")
 val loan=s.optJSONObject("loan_timing")?.let{JSONObject(it.toString()).put("available",it.optBoolean("available")&&message.isEmpty()&&s.optString("error").isEmpty())}
 LoanTiming(loan,loan?.optBoolean("available")==true,loan?.optString("market_date")?:"—")
 Panel{
  Text("연결 계좌",fontWeight=FontWeight.Bold)
  if(a!=null){
   var stocks=0.0;val positions=a.getJSONArray("positions")
   for(i in 0 until positions.length()){val p=positions.getJSONObject(i);stocks+=p.getDouble("quantity")*p.getDouble("price")}
   Text(won((stocks+a.getDouble("cash_usd"))*a.getDouble("fx")+a.getDouble("cash_krw")),fontSize=27.sp,fontWeight=FontWeight.Bold)
   Text("미국 USD 주식 + 원화·달러 현금",fontSize=12.sp)
   Text("달러 현금 ${usd(a.getDouble("cash_usd"))}\n매수가능 ${usd(a.getDouble("buying_power"))}")
   Text("조회 ${a.optString("as_of")}",fontSize=10.sp)
   for(i in 0 until positions.length()){val p=positions.getJSONObject(i);if(p.getDouble("quantity")>0)Text("${p.getString("symbol")} · ${p.getString("quantity")}주 · ${usd(p.getDouble("price"))}",fontSize=12.sp)}
  }else Text("동기화하면 보유잔고가 표시됩니다.")
  OutlinedButton(enabled=!busy,onClick={call("sync")}){Text(if(busy)"확인 중…" else "계좌·체결 동기화")}
 }
 val p=s.optJSONObject("plan")
 Panel{
  Text("다음 주문안",fontWeight=FontWeight.Bold)
  Text("정규장 지정가 · 다음 시가 체결 백테스트와 조건이 다릅니다. 매도 체결 후 매수안을 새로 확인합니다.",fontSize=12.sp)
  Button(enabled=!busy,onClick={call("plan")}){Text("주문안 계산")}
  if(p!=null){
   Text("${p.getString("model_id")} · ${p.getString("signal_date")}",fontSize=12.sp)
   Text("${stateLabel(p.getString("state"))} · 유효 시각 ${p.getString("expires_at")}",fontSize=11.sp)
   val orders=p.getJSONArray("orders");for(i in 0 until orders.length()){val o=orders.getJSONObject(i);Text("${o.getString("symbol")} ${if(o.getString("side")=="buy")"매수" else "매도"} ${o.getInt("quantity")}주 · 지정가 ${usd(o.getDouble("limit"))}")}
   Text("전량 체결 가정 현금 ${usd(p.getDouble("cash_after_usd"))}")
   val limits=p.getJSONArray("limits");for(i in 0 until limits.length())Text(limits.getString(i),color=Gold)
   val valid=try{Instant.now().isBefore(Instant.parse(p.getString("expires_at")))}catch(_:Exception){false}
   OutlinedButton(enabled=!busy&&p.getString("state")=="draft"&&orders.length()>0&&valid,onClick={call("simulate",JSONObject().put("plan_id",p.getString("id")))},modifier=Modifier.fillMaxWidth()){Text("관찰 기록 · 실제 전송 없음")}
   Button(enabled=!busy&&!paused&&mode=="confirm"&&p.getString("state")=="draft"&&orders.length()>0&&limits.length()==0&&valid,onClick={review=JSONObject(p.toString())},modifier=Modifier.fillMaxWidth()){Text(if(demo)"가상 체결 내용 확인" else "주문 내용 확인")}
  }
 }
 TextButton(onClick={records=!records}){Text(if(records)"상세 기록 접기" else "체결·일별 자산 기록 보기")}
 if(records)Panel{
  Text("체결 기록",fontWeight=FontWeight.Bold);val orders=s.getJSONArray("orders")
  if(orders.length()==0)Text("접수와 실제 체결을 구분해 기록합니다.",fontSize=12.sp)
  for(i in 0 until minOf(orders.length(),20)){val o=orders.getJSONObject(i);Text("${o.getString("symbol")} · ${o.getInt("quantity")}주 · ${stateLabel(o.getString("state"))}\n체결 ${o.optString("filled")}주 ${if(o.optBoolean("demo"))"· 예시" else "· 주문 ${o.optString("broker_id")}"}",fontSize=12.sp);HorizontalDivider()}
 }
 if(records)Panel{
  Text("일별 연결 자산",fontWeight=FontWeight.Bold);Text("입출금 포함 평가액입니다. 투자 손익과 구분해 보세요. 기존 내 자산 기록은 별도로 보존됩니다.",fontSize=12.sp)
  val history=s.getJSONArray("history");for(i in 0 until minOf(history.length(),10)){val h=history.getJSONObject(i);Text("${h.getString("day")} · ${won(h.getDouble("total_krw"))}")}
 }
 val unresolved=s.getJSONArray("orders")
 for(i in 0 until unresolved.length()){val o=unresolved.getJSONObject(i);if(o.optString("state")=="unknown")Panel{
  Text("${o.getString("symbol")} · 결과 미확인",fontWeight=FontWeight.Bold)
  Text("재전송을 중단했습니다. 메리츠 주문내역의 주문번호로 대조하세요.",fontSize=12.sp)
  OutlinedButton(enabled=!busy,onClick={resolving=o.getString("id");brokerId="";brokerDate=""}){Text("주문번호 대조")}
 }}
 resolving?.let{id->AlertDialog(onDismissRequest={resolving=null},title={Text("미확인 주문 대조")},text={Column{
  Text("메리츠 내역과 종목·수량·가격이 일치할 때만 연결합니다.")
  OutlinedTextField(brokerId,{brokerId=it},label={Text("주문번호")},singleLine=true)
  OutlinedTextField(brokerDate,{brokerDate=it},label={Text("주문일자 YYYYMMDD")},singleLine=true)
 }},confirmButton={Button(onClick={resolving=null;call("resolve",JSONObject().put("order_id",id).put("broker_id",brokerId).put("broker_date",brokerDate))},enabled=!busy){Text("증권사와 대조")}},dismissButton={TextButton(onClick={resolving=null}){Text("돌아가기")}})}
 if(records)Panel{Text("운용 기록",fontWeight=FontWeight.Bold);val events=s.getJSONArray("events");for(i in 0 until minOf(events.length(),10)){val e=events.getJSONObject(i);Text(e.getString("message"),fontSize=12.sp);Text(e.getString("at"),fontSize=10.sp)}}
 review?.let{r->AlertDialog(onDismissRequest={review=null},title={Text(if(demo)"가상 체결 확인" else "메리츠 주문 전송 확인")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
  Text(if(demo)"예시 계좌이며 실제 주문을 보내지 않습니다." else "아래 지정가 주문을 전송합니다. 접수 후 체결 상태를 확인하세요.")
  val orders=r.getJSONArray("orders");for(i in 0 until orders.length()){val o=orders.getJSONObject(i);Text("${o.getString("symbol")} · ${if(o.getString("side")=="buy")"매수" else "매도"} ${o.getInt("quantity")}주 · ${usd(o.getDouble("limit"))}")}
  Text("전량 체결 가정 현금 ${usd(r.getDouble("cash_after_usd"))}")
 }},confirmButton={Button(onClick={review=null;call("approve",JSONObject().put("plan_id",r.getString("id")))},enabled=!busy){Text(if(demo)"가상 체결 기록" else "확인한 주문 전송")}},dismissButton={TextButton(onClick={review=null}){Text("돌아가기")}})}
}
