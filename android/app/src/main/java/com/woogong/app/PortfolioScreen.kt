package com.woogong.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

private val Muted:Color @Composable get()=MaterialTheme.colorScheme.onSurfaceVariant
private val Positive=Color(0xffe15c68)
private val Negative=Color(0xff4385ce)
internal fun won(v:Double)=String.format(Locale.KOREA,"%,.0f원",v)
internal fun usd(v:Double)=String.format(Locale.US,"$%,.2f",v)
private fun qty(v:Double)=String.format(Locale.US,"%.4f",v).trimEnd('0').trimEnd('.')
private fun signedWon(v:Double)=(if(v>0)"+" else if(v<0)"−" else "")+won(abs(v))
private fun tone(v:Double)=if(v>=0)Positive else Negative

@Composable fun PortfolioScreen(snap:JSONObject,status:JSONObject,resumeTick:Int){
 val c=LocalContext.current
 var state by remember{mutableStateOf(PortfolioState(null,emptyList()))}
 var editing by remember{mutableStateOf(false)}
 var message by remember{mutableStateOf("")}
 var flow by remember{mutableStateOf(false)}
 var simulation by remember{mutableStateOf(false)}
 var backupPreview by remember{mutableStateOf<String?>(null)}
 var selectedDay by remember{mutableStateOf<DailyMark?>(null)}
 fun reload(){state=PortfolioStore.read(c)}
 LaunchedEffect(resumeTick,snap.toString(),status.toString()){
  try{PortfolioStore.recordDaily(c,snap,status);reload()}catch(e:Exception){message=e.message?:"자산 기록을 확인하세요"}
 }
 val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
  if(uri!=null)try{c.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use{it.write(PortfolioStore.export(c))}?:error("파일을 열 수 없습니다");message="잔고와 일별 기록을 백업했습니다"}catch(e:Exception){message=e.message?:"백업 실패"}
 }
 val restore=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->
  if(uri!=null)try{
   val text=c.contentResolver.openInputStream(uri)?.bufferedReader()?.use{reader->val out=StringBuilder();val buf=CharArray(4096);var n=reader.read(buf);while(n!=-1){out.append(buf,0,n);require(out.length<=10_000_000){"백업 파일이 너무 큽니다"};n=reader.read(buf)};out.toString()}?:error("파일을 열 수 없습니다")
   PortfolioStore.decode(text);backupPreview=text
  }catch(e:Exception){message=e.message?:"지원하는 우공이산 백업 파일을 선택하세요"}
 }
 if(message.isNotEmpty())Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(16.dp)){Text(message,Modifier.padding(14.dp),color=Mint,fontSize=12.sp)}
 if(editing){AccountEditor(state.account,saved={a->PortfolioStore.saveAccount(c,a);reload();editing=false;message="메리츠 잔고를 저장했습니다"},cancel={editing=false});return}
 Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
  Column{Text("내 자산",fontSize=25.sp,fontWeight=FontWeight.Bold);Text("메리츠증권 · 직접 확인한 잔고",fontSize=12.sp,color=Muted)}
  FilledTonalIconButton(onClick={editing=true}){Icon(Icons.Outlined.Edit,contentDescription="잔고 입력 및 수정")}
 }
 val a=state.account
 if(a==null){
  Surface(shape=RoundedCornerShape(28.dp),color=Color(0xff192441),contentColor=Color.White){Column(Modifier.padding(26.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
   Icon(Icons.Outlined.AccountBalanceWallet,null,tint=Color(0xffa99cff),modifier=Modifier.size(36.dp))
   Text("내 돈의 흐름을,\n한눈에 담아보세요.",fontSize=26.sp,lineHeight=36.sp,fontWeight=FontWeight.Bold,color=Color.White)
   Text("투입금과 보유 주식, 원화·달러 현금을 등록하면 평가액과 목표 매매 수량을 함께 볼 수 있어요.",color=Color(0xffb2b9cf),lineHeight=22.sp)
   Button(onClick={editing=true},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Mint)){Text("내 메리츠 잔고 등록하기")}
  }}
  Panel{Text("계좌 연결 방식",fontWeight=FontWeight.Bold);Text("증권사 로그인 없이 잔고를 직접 맞춥니다. 매매·입출금 뒤 수량과 현금을 수정하고, 시세가 연결되면 평가는 자동으로 갱신됩니다.",color=Muted)}
  TextButton(onClick={restore.launch("application/json")}){Icon(Icons.Outlined.Restore,null);Spacer(Modifier.width(8.dp));Text("기존 우공이산 백업 불러오기")}
 }else{
  val priced=PortfolioStore.priced(a,snap,status)
  val v=try{PortfolioEngine.value(priced.account)}catch(_:Exception){null}
  val previous=state.history.lastOrNull{it.date<PortfolioStore.today()}
  val delta=if(v!=null&&previous!=null&&priced.recordable)v.totalKrw-previous.totalKrw-(a.flowKrw-previous.flowKrw) else null
  Surface(shape=RoundedCornerShape(28.dp),color=Color(0xff192441),contentColor=Color.White){Column(Modifier.background(Brush.linearGradient(listOf(Color(0xff192441),Color(0xff34305b)))).padding(25.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("총 평가자산",color=Color(0xffb8bfd5),fontSize=12.sp);Text("MERITZ",color=Color(0xffd4caff),fontSize=11.sp,letterSpacing=2.sp)}
   Text(v?.let{won(it.totalKrw)}?:"가격 입력 필요",fontSize=31.sp,fontWeight=FontWeight.Bold,color=Color.White,letterSpacing=(-1).sp)
   Text(v?.let{"달러 자산 ${usd(it.totalUsd)} + 원화 현금 ${won(a.cashKrw)}"}?:"보유 종목의 기준가격과 환율을 입력해주세요",fontSize=11.sp,color=Color(0xffb8bfd5))
   HorizontalDivider(color=Color(0xff505a77),modifier=Modifier.padding(vertical=7.dp))
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Column{Text(if(previous?.date==java.time.LocalDate.parse(PortfolioStore.today()).minusDays(1).toString())"전일 대비 · 입출금 제외" else "직전 기록 대비 · 입출금 제외",color=Color(0xffb8bfd5),fontSize=11.sp);Text(delta?.let{signedWon(it)}?:"기록이 쌓이면 표시됩니다",color=Color(0xffb5a9ff),fontWeight=FontWeight.Bold,fontSize=17.sp)};Icon(if(delta==null)Icons.Outlined.AccountBalanceWallet else if(delta>=0)Icons.Outlined.TrendingUp else Icons.Outlined.TrendingDown,null,tint=Color(0xffb5a9ff),modifier=Modifier.size(32.dp))}
  }}
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
   SmallStat("순투입 원금",if(a.capitalKnown)won(a.capitalKrw) else "원금 입력 필요",Modifier.weight(1f))
   SmallStat("순투입금 대비 손익",v?.profitKrw?.let{signedWon(it)}?:"원금 입력 필요",Modifier.weight(1f),v?.profitKrw?.let{tone(it)}?:Ink)
  }
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
   OutlinedButton(onClick={flow=true},modifier=Modifier.weight(1f)){Icon(Icons.Outlined.SwapVert,null,Modifier.size(18.dp));Text("입금 · 출금")}
   OutlinedButton(onClick={editing=true},modifier=Modifier.weight(1f)){Icon(Icons.Outlined.Edit,null,Modifier.size(16.dp));Text("잔고 맞추기")}
  }
  Text("가격 ${priced.priceDate} · ${priced.source} / 환율 ${a.fxDate} · ${"%,.2f".format(a.fx)}원\n메리츠 잔고 확인 ${a.confirmedDate}. 원화 환산은 입력한 기준환율을 사용합니다.",fontSize=11.sp,color=Muted,lineHeight=17.sp)
  if(!priced.recordable)Text("새 종가 연결 또는 오늘 기준가격 입력 후 일별 기록이 갱신됩니다. 가격이 없는 날짜의 자산 변화를 만들지 않습니다.",fontSize=12.sp,color=Gold)
  Panel{
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("자산의 흐름",fontWeight=FontWeight.Bold);Text("평가액 / 순투입금",color=Muted,fontSize=11.sp)}
   if(state.history.size>=2)WealthChart(state.history) else Box(Modifier.fillMaxWidth().height(100.dp),contentAlignment=Alignment.Center){Text("두 날짜 이상의 기록이 쌓이면 그래프가 나타나요",color=Muted,fontSize=12.sp)}
   Text("한국 날짜별 1개 기록 · 추가 입금은 수익에서 제외",fontSize=11.sp,color=Muted)
  }
  Panel{
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("보유 주식",fontWeight=FontWeight.Bold);Text("USD 기준",color=Muted,fontSize=11.sp)}
   priced.account.positions.filter{it.quantity>0}.forEach{p->
    Row(Modifier.fillMaxWidth().padding(vertical=7.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
     Row(verticalAlignment=Alignment.CenterVertically){Surface(shape=RoundedCornerShape(13.dp),color=MaterialTheme.colorScheme.primaryContainer){Text(p.symbol.take(2),Modifier.padding(11.dp),color=Mint,fontWeight=FontWeight.Bold,fontSize=12.sp)};Spacer(Modifier.width(11.dp));Column{Text(p.symbol,fontWeight=FontWeight.Bold);Text("${qty(p.quantity)}주 · 1주 ${if(p.price>0)usd(p.price) else "가격 필요"}",color=Muted,fontSize=11.sp)}}
     Column(horizontalAlignment=Alignment.End){Text(if(p.price>0)usd(p.quantity*p.price) else "—",fontWeight=FontWeight.SemiBold);if(p.average>0&&p.price>0)Text((if(p.price>=p.average)"+" else "−")+usd(abs((p.price-p.average)*p.quantity)),color=tone(p.price-p.average),fontSize=11.sp) else Text("평균단가 미입력",color=Muted,fontSize=10.sp)}
    }
   }
   HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant);StatLine("달러 현금",usd(a.cashUsd));StatLine("원화 현금",won(a.cashKrw))
  }
  Panel{
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("목표 매매 계획",fontWeight=FontWeight.Bold);Surface(shape=RoundedCornerShape(8.dp),color=MaterialTheme.colorScheme.primaryContainer){Text("정수주",Modifier.padding(8.dp,4.dp),color=Mint,fontSize=10.sp)}}
   val stale=Data.stale(snap,status)
   Text("${snap.optString("version")} · ${snap.optString("market_date")} 목표 비중",color=Muted,fontSize=11.sp)
   if(stale){
    Text("현재 목표는 과거 자료입니다. 최신 신호 연결 전에는 과거 비중으로 시뮬레이션만 할 수 있습니다.",fontSize=12.sp,color=Gold)
    Row(verticalAlignment=Alignment.CenterVertically){Switch(checked=simulation,onCheckedChange={simulation=it});Spacer(Modifier.width(8.dp));Text("과거 목표로 시뮬레이션",fontSize=12.sp)}
   }
   if(!stale||simulation){
    val weights=snap.getJSONObject("target").let{w->w.keys().asSequence().associateWith{w.getDouble(it)}}
    val result=runCatching{PortfolioEngine.plan(priced.account,weights)}
    val plan=result.getOrNull()
    if(plan==null)Text(result.exceptionOrNull()?.message?:"가격과 잔고를 입력하세요",color=Gold,fontSize=12.sp)
    else{
     if(stale)Text("과거 목표 시뮬레이션",color=Gold,fontWeight=FontWeight.Bold)
     Text(if(plan.bandGap>=snap.optDouble("rebalance_band",.125))"모델의 재조정 문턱을 넘었습니다" else "재조정 문턱 이내 · 참고용 수량",color=Mint,fontSize=12.sp)
     plan.rows.filter{it.current>0||it.targetWeight>0}.forEach{r->
      Row(Modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween){Column{Text(r.symbol,fontWeight=FontWeight.Bold);Text("현재 ${qty(r.current)}주 → 계획 후 ${qty(r.after)}주",color=Muted,fontSize=11.sp)};Text(when{r.sell>0->"${r.sell}주 매도";r.buy>0->"${r.buy}주 매수";else->"유지"},color=when{r.sell>0->Negative;r.buy>0->Positive;else->Muted},fontWeight=FontWeight.Bold)}
     }
     HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant);StatLine("매매 후 달러 현금",usd(plan.cashAfter),Mint)
     StatLine("목표 현금 / 최소 유보",usd(plan.desiredCash));StatLine("예상 비용·가격 여유",usd(plan.estimatedCost))
     Text("달러 운용자산 ${usd(plan.budget)} 기준. 원화 현금 ${won(a.cashKrw)}과 전략 외 주식 ${usd(plan.outsideStrategy)}은 유지합니다. 매도 대금 사용이 가능하다는 가정이며, 실제 매수가능금액을 확인해주세요.",color=Muted,fontSize=11.sp,lineHeight=17.sp)
     Text("수수료 편도 ${"%.2f".format(a.fee*100)}% · 가격 여유 ${"%.2f".format(a.slippage*100)}%. 매도 후 매수 순서의 참고 계획이며, 체결 결과와 보유량은 자동 변경하지 않습니다.",color=Muted,fontSize=11.sp,lineHeight=17.sp)
     if(plan.fractionalRemainder)Text("소수점 잔여 주식은 이 정수주 계획에서 유지됩니다.",fontSize=11.sp,color=Gold)
     if(plan.cashAfter+1e-6<plan.desiredCash)Text("수수료·정수주 때문에 목표 현금에 미달합니다. 추가 매수는 제외했습니다.",fontSize=11.sp,color=Gold)
    }
   }
  }
  Panel{
   Text("일별 자산 기록",fontWeight=FontWeight.Bold)
   if(state.history.isEmpty())Text("첫 잔고와 가격을 확인하면 기록이 시작됩니다",color=Muted,fontSize=12.sp)
   val history=state.history
   history.takeLast(10).asReversed().forEach{h->val prev=history.lastOrNull{it.date<h.date}
    TextButton(onClick={selectedDay=h},modifier=Modifier.fillMaxWidth(),contentPadding=PaddingValues(0.dp)){Row(Modifier.fillMaxWidth().padding(vertical=6.dp),horizontalArrangement=Arrangement.SpaceBetween){Column{Text(h.date,fontSize=12.sp);Text("가격 ${h.priceDate}",fontSize=10.sp,color=Muted)};Column(horizontalAlignment=Alignment.End){Text(won(h.totalKrw),fontWeight=FontWeight.Bold,fontSize=13.sp);if(prev!=null){val change=PortfolioEngine.movement(h,prev);Text(signedWon(change)+" · 입출금 제외",fontSize=10.sp,color=tone(change))}else Text("첫 기록",fontSize=10.sp,color=Muted)}}}
   }
  }
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={export.launch("woogong-assets-${PortfolioStore.today()}.json")}){Icon(Icons.Outlined.FileDownload,null,Modifier.size(17.dp));Text("자산 백업")};TextButton(onClick={restore.launch("application/json")}){Text("백업 복원")}}
  Text("자산 정보는 휴대폰에 저장됩니다. 앱을 삭제하기 전 백업해주세요. 실제 계좌 자동 동기화는 연결되지 않았습니다.",fontSize=11.sp,color=Muted,lineHeight=17.sp)
 }
 if(flow)FlowDialog(close={flow=false},save={amount,currency,deposit->PortfolioStore.cashFlow(c,amount,currency,deposit);reload();flow=false;message="입출금을 반영했습니다"})
 selectedDay?.let{h->AlertDialog(onDismissRequest={selectedDay=null},title={Text(h.date+" 자산 기록")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Text(won(h.totalKrw),fontWeight=FontWeight.Bold,fontSize=22.sp);Text("가격 ${h.priceDate} · 환율 ${h.fxDate}",fontSize=11.sp,color=Muted);h.positions.filter{it.quantity>0}.forEach{p->StatLine("${p.symbol} · ${qty(p.quantity)}주",usd(p.quantity*p.price));Text("1주 ${usd(p.price)}",fontSize=10.sp,color=Muted)};StatLine("현금 합계 · 원화 환산",won(h.cashKrw))}},confirmButton={TextButton(onClick={selectedDay=null}){Text("닫기")}})}
 backupPreview?.let{raw->val parsed=PortfolioStore.decode(raw);AlertDialog(onDismissRequest={backupPreview=null},title={Text("자산 백업을 복원할까요?")},text={Text("${parsed.account?.broker} · 일별 기록 ${parsed.history.size}개\n현재 휴대폰의 잔고와 기록을 이 백업으로 교체합니다.")},confirmButton={TextButton(onClick={try{PortfolioStore.write(c,parsed);reload();backupPreview=null;message="백업을 복원했습니다"}catch(e:Exception){message=e.message?:"복원 실패"}}){Text("복원")}},dismissButton={TextButton(onClick={backupPreview=null}){Text("취소")}})}
}

@Composable private fun SmallStat(label:String,value:String,modifier:Modifier,color:Color=Ink){Surface(modifier,shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){Text(label,fontSize=10.sp,color=Muted);Text(value,fontSize=15.sp,fontWeight=FontWeight.Bold,color=color)}}}
@Composable private fun StatLine(label:String,value:String,color:Color=Ink){Row(Modifier.fillMaxWidth().padding(vertical=5.dp),horizontalArrangement=Arrangement.SpaceBetween){Text(label,color=Muted,fontSize=12.sp);Text(value,color=color,fontSize=13.sp,fontWeight=FontWeight.SemiBold)}}
@Composable private fun WealthChart(history:List<DailyMark>){val accent=Mint;Canvas(Modifier.fillMaxWidth().height(155.dp).padding(top=15.dp,bottom=5.dp)){
 val showCapital=history.all{it.capitalKnown};val values=history.flatMap{if(showCapital)listOf(it.totalKrw,it.capitalKrw) else listOf(it.totalKrw)};val lo=values.min();val range=(values.max()-lo).coerceAtLeast(1.0)
 for(series in 0..(if(showCapital)1 else 0)){val path=Path();history.forEachIndexed{i,h->val x=size.width*i/(history.size-1);val v=if(series==0)h.totalKrw else h.capitalKrw;val y=size.height*(1-((v-lo)/range).toFloat());if(i==0)path.moveTo(x,y)else path.lineTo(x,y)};drawPath(path,if(series==0)accent else Color(0xffc9ceda),style=androidx.compose.ui.graphics.drawscope.Stroke(if(series==0)4f else 2f))}
}}
private data class Draft(val symbol:String,val quantity:String="",val average:String="",val price:String="")
@Composable private fun MoneyField(label:String,text:String,change:(String)->Unit,modifier:Modifier=Modifier){OutlinedTextField(text,change,label={Text(label,fontSize=12.sp)},modifier=modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(14.dp),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))}
@Composable private fun AccountEditor(original:Account?,saved:(Account)->Unit,cancel:()->Unit){
 val context=LocalContext.current
 val legacy=remember{try{JSONObject(Data.prefs(context).getString("holdings","{}")!!)}catch(_:Exception){JSONObject()}}
 var cashUsd by remember{mutableStateOf(original?.cashUsd?.toString()?:legacy.optDouble("CASH",0.0).toString())}
 var cashKrw by remember{mutableStateOf(original?.cashKrw?.toString()?:"")};var capital by remember{mutableStateOf(original?.takeIf{it.capitalKnown}?.capitalKrw?.toString()?:"")}
 var fx by remember{mutableStateOf(original?.fx?.takeIf{it>0}?.toString()?:"")};var priceDate by remember{mutableStateOf(original?.priceDate?:PortfolioStore.today())};var fxDate by remember{mutableStateOf(original?.fxDate?:PortfolioStore.today())}
 var fee by remember{mutableStateOf(((original?.fee?:.001)*100).toString())};var slip by remember{mutableStateOf(((original?.slippage?:.001)*100).toString())};var reserve by remember{mutableStateOf(original?.reserveUsd?.toString()?:"0")}
 var drafts by remember{mutableStateOf((PortfolioEngine.symbols+(original?.positions?.map{it.symbol}?:emptyList())).distinct().map{s->val p=original?.positions?.find{it.symbol==s};Draft(s,p?.quantity?.toString()?:legacy.optDouble(s,0.0).toString(),p?.average?.toString()?:"",p?.price?.takeIf{it>0}?.toString()?:"")})}
 var custom by remember{mutableStateOf("")};var error by remember{mutableStateOf("")}
 fun number(s:String):Double=if(s.isBlank())0.0 else s.replace(",","").trim().toDouble()
 Text("메리츠 잔고 맞추기",fontSize=24.sp,fontWeight=FontWeight.Bold)
 Text("실제 계좌의 수량과 현금을 입력하세요. 매매한 뒤에도 여기에서 체결 결과를 맞춥니다.",color=Muted,fontSize=12.sp,lineHeight=19.sp)
 Panel{Text("원금과 현금",fontWeight=FontWeight.Bold);MoneyField("누적 순투입금 (원) · 입금−출금",capital,{capital=it});MoneyField("달러 현금 (USD)",cashUsd,{cashUsd=it});MoneyField("원화 현금 (KRW)",cashKrw,{cashKrw=it});Text("입출금은 내 자산의 입금·출금 버튼으로 기록하세요. 이 원금 칸의 수정은 과거 원금 정정으로 취급합니다. 모르면 비워두면 누적 손익은 숨깁니다.",fontSize=11.sp,color=Muted)}
 Panel{Text("평가 기준",fontWeight=FontWeight.Bold);MoneyField("1달러당 원화 환율",fx,{fx=it});OutlinedTextField(fxDate,{fxDate=it},label={Text("환율 기준일 YYYY-MM-DD")},singleLine=true,modifier=Modifier.fillMaxWidth());OutlinedTextField(priceDate,{priceDate=it},label={Text("아래 가격의 기준일 YYYY-MM-DD")},singleLine=true,modifier=Modifier.fillMaxWidth());Text("자동 시세가 없을 때는 메리츠 앱에 표시된 가격을 입력하세요. 최신가로 가장하지 않고 이 날짜를 함께 표시합니다.",color=Muted,fontSize=11.sp)}
 drafts.forEachIndexed{i,p->Panel{
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(p.symbol,fontWeight=FontWeight.Bold,color=Mint);if(p.symbol !in PortfolioEngine.symbols)TextButton(onClick={drafts=drafts.filterIndexed{j,_->j!=i}}){Text("제외")}}
  MoneyField("보유 수량 (주)",p.quantity,{v->drafts=drafts.toMutableList().also{it[i]=p.copy(quantity=v)}})
  MoneyField("매수 평균단가 (USD) · 선택",p.average,{v->drafts=drafts.toMutableList().also{it[i]=p.copy(average=v)}})
  MoneyField("1주 기준가격 (USD)",p.price,{v->drafts=drafts.toMutableList().also{it[i]=p.copy(price=v)}})
 }}
 Panel{Text("다른 미국주식도 함께 기록",fontWeight=FontWeight.Bold);OutlinedTextField(custom,{custom=it.uppercase(Locale.US)},label={Text("종목 코드 · 예: AAPL")},singleLine=true,modifier=Modifier.fillMaxWidth());TextButton(onClick={val s=custom.trim();if(!s.matches(Regex("[A-Z][A-Z0-9.\\-]{0,14}"))||drafts.any{it.symbol==s})error="중복되지 않는 미국주식 종목 코드를 입력하세요" else{drafts=drafts+Draft(s);custom=""}}){Text("종목 추가")};Text("전략 외 종목은 평가에 포함하고 매매 계획에서는 유지합니다. 해당 가격은 직접 갱신합니다.",fontSize=11.sp,color=Muted)}
 Panel{Text("매매 계획의 비용과 현금",fontWeight=FontWeight.Bold);MoneyField("편도 예상 수수료 (%)",fee,{fee=it});MoneyField("체결가격 여유 (%)",slip,{slip=it});MoneyField("최소 유지할 달러 현금",reserve,{reserve=it})}
 if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
 Button(onClick={try{val a=Account(positions=drafts.map{Position(it.symbol,number(it.quantity),number(it.average),number(it.price))},cashUsd=number(cashUsd),cashKrw=number(cashKrw),capitalKrw=number(capital),fx=number(fx),priceDate=priceDate.trim(),fxDate=fxDate.trim(),fee=number(fee)/100,slippage=number(slip)/100,reserveUsd=number(reserve),capitalKnown=capital.isNotBlank(),flowKrw=original?.flowKrw?:0.0);PortfolioEngine.validate(a);require(a.fx>0){"환율을 입력하세요"};saved(a)}catch(e:Exception){error=e.message?:"숫자를 확인하세요"}},modifier=Modifier.fillMaxWidth(),contentPadding=PaddingValues(16.dp)){Text("실제 잔고 저장하기",fontWeight=FontWeight.Bold)}
 TextButton(onClick=cancel,modifier=Modifier.fillMaxWidth()){Text("취소")}
}
@Composable private fun FlowDialog(close:()->Unit,save:(Double,String,Boolean)->Unit){
 var amount by remember{mutableStateOf("")};var currency by remember{mutableStateOf("KRW")};var deposit by remember{mutableStateOf(true)};var error by remember{mutableStateOf("")}
 AlertDialog(onDismissRequest=close,title={Text("입금 · 출금 기록")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
  Row{FilterChip(deposit,{deposit=true},label={Text("입금")});Spacer(Modifier.width(8.dp));FilterChip(!deposit,{deposit=false},label={Text("출금")})}
  Row{FilterChip(currency=="KRW",{currency="KRW"},label={Text("원화")});Spacer(Modifier.width(8.dp));FilterChip(currency=="USD",{currency="USD"},label={Text("달러")})}
  MoneyField("금액",amount,{amount=it});Text("현금과 순투입금에 함께 반영됩니다. 달러 입출금은 저장된 기준환율로 원화 원금을 기록합니다. 과거 입출금을 소급 입력하는 기능은 아닙니다.",fontSize=11.sp,color=Muted);if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
 }},confirmButton={TextButton(onClick={try{val n=amount.replace(",","").toDouble();require(n.isFinite()&&n>0);save(n,currency,deposit)}catch(e:Exception){error=e.message?:"0보다 큰 금액을 입력하세요"}}){Text("반영")}},dismissButton={TextButton(onClick=close){Text("취소")}})
}
