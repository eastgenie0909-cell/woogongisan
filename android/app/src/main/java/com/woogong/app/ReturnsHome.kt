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
  if(TradingConnection.url(c).isNotBlank())return "—" to "연결 계좌 · 순투입금 확인 필요"
  return try{
   val a=PortfolioStore.read(c).account?:return "—" to "내 자산에서 원금·잔고 등록"
   val v=PortfolioEngine.value(a);val r=if(a.capitalKnown)percent(v.totalKrw,a.capitalKrw) else null
   (r?.let{"%+.2f%%".format(it)}?:"—") to "수동 기록 · 가격 ${a.priceDate} / 환율 ${a.fxDate}"
  }catch(_:Exception){"—" to "원금·잔고 확인 필요"}
 }
}

@Composable internal fun ReturnsHome(s:JSONObject,stale:Boolean){
 val c=LocalContext.current;val summary=ReturnsSummary.read(c)
 var month by remember{mutableStateOf(true)}
 val light=Color(0xffa2b5c9);val mint=Color(0xff75dfc5)
 Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(26.dp),colors=CardDefaults.cardColors(containerColor=Color(0xff142333),contentColor=Color.White)){
  Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   Text("나의 수익률",fontSize=18.sp,fontWeight=FontWeight.Bold)
   Text("총 수익률 · 순투입금 대비",fontSize=12.sp,color=light)
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(summary.first,fontSize=38.sp,fontWeight=FontWeight.Bold,color=mint);Column{Text("이번 달",fontSize=11.sp,color=light);Text("—",fontSize=23.sp,color=mint)}}
   Text(summary.second,fontSize=10.sp,color=light)
   HorizontalDivider(color=Color(0xff304153))
   Text("내 계좌와 TQQQ 비교",fontWeight=FontWeight.Bold)
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){TextButton(onClick={month=true}){Text(if(month)"● 이번 달" else "이번 달",color=mint)};TextButton(onClick={month=false}){Text(if(!month)"● 누적" else "누적",color=mint)}}
   Text("● 내 계좌    ● TQQQ",fontSize=11.sp,color=light)
   Box(Modifier.fillMaxWidth().height(90.dp).background(Color(0xff0e1c2a),RoundedCornerShape(12.dp)).padding(14.dp)){Text("${if(month)"월간" else "누적"} 비교 준비 중\n입출금 기록·동일 기간 TQQQ 자료가 필요합니다.",fontSize=12.sp,color=light)}
   Text("같은 기간·원화 기준 · 시작점 0%",fontSize=10.sp,color=light)
   HorizontalDivider(color=Color(0xff304153))
   Text("모델 판단 · 상대강도",fontWeight=FontWeight.Bold)
   Lines(s.getJSONObject("charts").getJSONArray("relative"),listOf("relative_fast","relative_slow"))
   Text("EMA20 / EMA45 · ${s.optString("market_date")}${if(stale)" 과거/갱신 대기" else " 확정 종가"}",fontSize=10.sp,color=light)
   Text("목표  ${Data.allocation(s)}",fontSize=12.sp)
  }
 }
 LoanTiming(s.optJSONObject("loan_timing"),!stale,s.optString("market_date"),s.optString("valid_until"))
}
