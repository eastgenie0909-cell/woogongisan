package com.woogong.app

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

object ReturnsSummary {
 fun percent(total:Double,capital:Double):Double?=if(total.isFinite()&&total>=0&&capital.isFinite()&&capital>0)((total/capital-1)*100).takeIf{it.isFinite()} else null
 fun read(c:Context):Pair<String,String> {
  if(MeritzAccount.connected(c))return try{
   val a=MeritzAccount.cached(c)?.optJSONObject("account")?:return "—" to "메리츠 계좌 조회 대기"
   val positions=a.getJSONArray("positions");var cost=0.0;var profit=0.0
   for(i in 0 until positions.length()){val p=positions.getJSONObject(i);cost+=p.getDouble("cost_krw");profit+=p.getDouble("profit_krw")}
   val percentage=if(cost>0)100*profit/cost else null
   val old=MeritzAccount.failed(c)||WidgetSummary.expired(java.time.Instant.parse(a.getString("as_of")),java.time.Instant.now())
   (percentage?.takeIf{it.isFinite()}?.let{"%+.2f%%".format(it)}?:"—") to "보유 종목 평가 · ${a.getString("as_of")}${if(old)" · 갱신 필요" else ""}"
  }catch(_:Exception){"—" to "메리츠 계좌 갱신 필요"}
  if(TradingConnection.url(c).isNotBlank())return "—" to "연결 계좌 · 순투입금 확인 필요"
  return try{
   val a=PortfolioStore.read(c).account?:return "—" to "내 자산에서 원금·잔고 등록"
   val v=PortfolioEngine.value(a);val r=if(a.capitalKnown)percent(v.totalKrw,a.capitalKrw) else null
   (r?.let{"%+.2f%%".format(it)}?:"—") to "수동 기록 · 가격 ${a.priceDate} / 환율 ${a.fxDate}"
  }catch(_:Exception){"—" to "원금·잔고 확인 필요"}
 }
}

@Composable internal fun ReturnsHome(s:JSONObject,stale:Boolean){
 val c=LocalContext.current
 var period by remember{mutableStateOf(Data.prefs(c).getString("performance_period","1m")?:"1m")}
 val window=Performance.read(s,period)
 val light=Color(0xffa2b5c9)
 Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(26.dp),colors=CardDefaults.cardColors(containerColor=Color(0xff142333),contentColor=Color.White)){
  Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   Text("우공이산 vs 시장",fontSize=24.sp,fontWeight=FontWeight.Bold)
   Text("모델 · TQQQ · 버크셔 B",fontSize=12.sp,color=light)
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
    Performance.periods.forEach{(key,label)->TextButton(modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=0.dp),onClick={period=key;Data.prefs(c).edit().putString("performance_period",key).apply();WoogongWidget.updateAll(c)}){Text(if(period==key)"● $label" else label,color=if(period==key)PerformanceColors[0] else light,fontSize=12.sp)}}
   }
   if(window==null){Text("비교 자료를 불러오는 중입니다. 최신 결과 확인을 눌러주세요.",fontSize=13.sp,color=light)}
   else {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
     listOf("우공이산" to window.last.model,"TQQQ" to window.last.tqqq,"버크셔 B" to window.last.brkb).forEachIndexed{i,(name,value)->Column(Modifier.weight(1f)){Text("● $name",fontSize=11.sp,color=PerformanceColors[i]);Text(Performance.percent(value),fontSize=17.sp,fontWeight=FontWeight.Bold,color=PerformanceColors[i])}}
    }
    PerformanceChart(window)
    Text("동일 기간 · 시작 0% · USD · 세전",fontSize=11.sp,color=light)
    Text("모델 시뮬레이션 · 다음 종가·편도 0.1% 비용 반영\n비교 종목은 배당·분할 반영 수정종가, 매수 후 보유",fontSize=10.sp,color=light,lineHeight=16.sp)
   }
   HorizontalDivider(color=Color(0xff304153))
   Text("${s.optString("market_date")} 미국 종가${if(stale)" · 이전 자료 / 갱신 필요" else " · 갱신 완료"}",fontSize=11.sp,color=if(stale)Gold else light)
   Text("모델 목표  ${Data.allocation(s)}",fontSize=12.sp)
  }
 }
 LoanTiming(s.optJSONObject("loan_timing"),!stale,s.optString("market_date"),s.optString("valid_until"))
}
