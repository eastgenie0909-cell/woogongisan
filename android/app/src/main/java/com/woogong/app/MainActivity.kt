package com.woogong.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

internal val Ink=Color(0xff18213c)
internal val Mint=Color(0xff6654e8)
internal val Gold=Color(0xffae8545)
class MainActivity:ComponentActivity(){
 private var resumeTick by mutableIntStateOf(0)
 override fun onResume(){super.onResume();resumeTick++}
 private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()){}
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)
  if(android.os.Build.VERSION.SDK_INT>=33)permission.launch(Manifest.permission.POST_NOTIFICATIONS)
  if(FirebaseApp.getApps(this).isNotEmpty())FirebaseMessaging.getInstance().token.addOnSuccessListener{Data.prefs(this).edit().putString("fcm_token",it).apply()}
  setContent{MaterialTheme(colorScheme=lightColorScheme(primary=Mint,background=Color(0xfff5f6fa),surface=Color.White,onSurface=Ink,onBackground=Ink)) { App() }}
 }
 @Composable fun App(){
  var tab by remember{mutableIntStateOf(0)};var snap by remember{mutableStateOf(Data.load(this,"snapshot"))};var status by remember{mutableStateOf(Data.load(this,"status"))}
  var back by remember{mutableStateOf(Data.load(this,"backtest"))};var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")};val scope=rememberCoroutineScope()
  fun refresh(){scope.launch{busy=true;error="";try{withContext(Dispatchers.IO){Data.sync(this@MainActivity)};snap=Data.load(this@MainActivity,"snapshot");status=Data.load(this@MainActivity,"status");back=Data.load(this@MainActivity,"backtest");WoogongWidget.updateAll(this@MainActivity)}catch(e:Exception){error=e.message?:"갱신 실패"}finally{busy=false}}}
  LaunchedEffect(Unit){if(Data.url(this@MainActivity).isNotBlank())refresh()}
  val clockTick by produceState(0L){while(true){delay(1000);value++}}
  LaunchedEffect(resumeTick){snap=Data.load(this@MainActivity,"snapshot");status=Data.load(this@MainActivity,"status");back=Data.load(this@MainActivity,"backtest")}
  val stale=remember(clockTick,snap,status,error){Data.stale(snap,status)||error.isNotEmpty()||Data.prefs(this@MainActivity).getString("sync_error","")!!.isNotEmpty()}
  val icons=listOf(Icons.Outlined.Home,Icons.Outlined.AccountBalanceWallet,Icons.Outlined.Lightbulb,Icons.Outlined.ShowChart,Icons.Outlined.Settings)
  Scaffold(containerColor=Color(0xfff5f6fa),topBar={Row(Modifier.fillMaxWidth().padding(24.dp,18.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Column{Text("우공이산",fontSize=27.sp,fontWeight=FontWeight.ExtraBold,letterSpacing=(-1).sp);Text("나의 자산, 흔들리지 않는 기준",fontSize=11.sp,color=Color(0xff8991a5))};Surface(color=Color(0xffeceafb),shape=RoundedCornerShape(20.dp)){Text(snap.optString("version").removePrefix("우공 "),Modifier.padding(12.dp,7.dp),fontSize=11.sp,color=Mint,fontWeight=FontWeight.Bold)}}},bottomBar={NavigationBar(containerColor=Color.White,tonalElevation=0.dp){listOf("오늘","내 자산","이유","성과","설정").forEachIndexed{i,label->NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(icons[i],contentDescription=label)},label={Text(label,fontSize=11.sp)},colors=NavigationBarItemDefaults.colors(selectedIconColor=Mint,selectedTextColor=Mint,indicatorColor=Color(0xffeeebff),unselectedIconColor=Color(0xff9aa2b5),unselectedTextColor=Color(0xff9aa2b5)))}}}){padding->
   Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
    if(stale&&tab!=1)Panel{Text(if(snap.optString("mode")!="live")"과거 자료 미리보기" else "새 종가 갱신 대기",color=Gold,fontWeight=FontWeight.Bold);Text("기준일 ${snap.optString("market_date")} · 현재 매매 신호로 사용하지 마세요.")}
    if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
    when(tab){
     0->{
      ReturnsHome(snap,stale)
      Panel{Text("메리츠증권 · 내 자산",fontWeight=FontWeight.Bold);Text("보유잔고와 순투입금, 하루의 변화를 한곳에서 확인하세요.",color=Color(0xff7b849a));TextButton(onClick={tab=1}){Text("내 자산 열기 →")}}
      Button(onClick={refresh()},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(if(busy)"확인 중…" else "최신 결과 확인")}
     }
     1->{
      var operation by remember{mutableStateOf(TradingConnection.url(this@MainActivity).isNotBlank())}
      Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
       FilterChip(selected=!operation,onClick={operation=false},label={Text("내 자산")})
       FilterChip(selected=operation,onClick={operation=true},label={Text("운용 센터")})
      }
      if(operation)TradingScreen() else PortfolioScreen(snap,status,resumeTick)
     }
     2->{
      Text("왜 이 목표인가요?",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
      Text("실제로 적용된 규칙과 숫자를 보여드립니다.")
      val reasons=snap.getJSONArray("reasons")
      for(i in 0 until reasons.length()){val reason=reasons.getJSONObject(i);Panel{Text(reason.getString("title"),color=if(reason.optBoolean("active"))Mint else Gold,fontWeight=FontWeight.Bold);Text(reason.getString("description"))}}
      Panel{Text("방어자산의 상대 흐름",fontWeight=FontWeight.Bold);Text("GLD / BRK.B · EMA84와 EMA378");Lines(snap.getJSONObject("charts").getJSONArray("defense"),listOf("defense_ratio","defense_fast","defense_slow"))}
      Panel{Text("QQQ 고점 대비 낙폭",fontWeight=FontWeight.Bold);Lines(snap.getJSONObject("charts").getJSONArray("drawdown"),listOf("qqq_drawdown"));Text("급락 상태는 25일 고점 대비 -6%에서 시작합니다. 해제에는 별도 회복·장기 추세 조건이 필요합니다.")}
     }
     3->{
      Text("과거 성과",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
      var extended by remember{mutableStateOf(false)}
      val ext=Data.prefs(this@MainActivity).getString("extended_backtest",null)
      Row{FilterChip(selected=!extended,onClick={extended=false},label={Text("동결 검증")});Spacer(Modifier.width(8.dp));FilterChip(selected=extended,onClick={extended=true},label={Text("최신 확장")},enabled=ext!=null)}
      val b=if(extended&&ext!=null)JSONObject(ext) else back
      if(Data.sameModel(snap,b)){
      Panel{Text(b.getString("label"),color=Mint);Text("종료 ${b.getString("data_end")}");Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Metric("연복리",b.getDouble("cagr"));Metric("최대 낙폭",b.getDouble("mdd"));Column{Text("CAGR/MDD");Text("%.2f".format(b.getDouble("calmar")),fontWeight=FontWeight.Bold)}};Lines(b.getJSONArray("curve"),listOf("nav"))}
      Panel{Text("낙폭의 깊이",fontWeight=FontWeight.Bold);Lines(b.getJSONArray("curve"),listOf("drawdown"));Text("거래일 종가 기준입니다. 장중 낙폭·실제 세금·환율·정수주 체결은 포함하지 않습니다.")}
      if(!extended)Panel{Text("장기 합성은 별도 참고",fontWeight=FontWeight.Bold);Text(b.optString("synthetic_label")+" · CAGR %.2f%%".format(b.optDouble("synthetic_cagr")*100))}
      Panel{Text(snap.optString("approval"),fontWeight=FontWeight.Bold);Text(snap.optString("research_note"))}
      if(!extended){Panel{Text("비용·체결 민감도",fontWeight=FontWeight.Bold);val cases=back.getJSONArray("scenarios");for(i in 0 until cases.length()){val row=cases.getJSONObject(i);val timing=if(row.getString("mode")=="next_open")"다음 시가" else "다음 종가";Text("$timing · 추가 비용 ${"%.1f".format(row.getDouble("slippage")*100)}% · 체결 ${"%.0f".format(row.getDouble("fill")*100)}%\nCAGR ${"%.2f".format(row.getDouble("cagr")*100)}% / MDD ${"%.2f".format(row.getDouble("mdd")*100)}% / 비율 ${"%.2f".format(row.getDouble("calmar"))}",style=MaterialTheme.typography.bodySmall)}}}
      }else Panel{Text("선택 모델의 백테스트 갱신 대기",fontWeight=FontWeight.Bold);Text("현재 목표와 같은 버전의 결과가 준비되면 표시합니다.")}
     }
     4->{
      Panel{Text("현재 모델",fontWeight=FontWeight.Bold);Text(snap.optString("version"));Text(snap.optString("approval"));Text("모델 변경은 서버 설정에서 반영되며, 보유량은 휴대폰에 유지됩니다.")}
      var base by remember{mutableStateOf(Data.url(this@MainActivity))}
      Panel{Text("GitHub Pages 연결",fontWeight=FontWeight.Bold);OutlinedTextField(base,{base=it},label={Text("https://계정.github.io/저장소")},modifier=Modifier.fillMaxWidth(),singleLine=true);Button(onClick={Data.prefs(this@MainActivity).edit().putString("url",base.trim()).apply();refresh()}){Text("저장하고 연결")};Text("HTTPS 결과 주소만 입력합니다. GitHub 비밀번호나 데이터 API 키는 앱에 넣지 않습니다.")}
      Panel{Text("장 마감 알림",fontWeight=FontWeight.Bold);Text("GitHub 계산 → 결과 게시 → Firebase 푸시 → 위젯 갱신. 예약 실행·통신·절전 설정에 따라 늦어질 수 있습니다.");Text(if(FirebaseApp.getApps(this@MainActivity).isEmpty())"Firebase 설정 파일이 아직 연결되지 않았습니다." else "Firebase 연결됨 · 휴대폰 알림 권한을 확인하세요.")
       Button(onClick={val token=Data.prefs(this@MainActivity).getString("fcm_token","")!!;(getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("기기 알림 토큰",token))}){Text("내 기기 알림 토큰 복사")}
      }
      Panel{Text("위젯 추가",fontWeight=FontWeight.Bold);Text("홈 화면을 길게 누르고 위젯 → 우공이산을 선택하세요. 5×2 크기로 수익률·TQQQ 비교 준비 상태와 목표 비중을 표시합니다.")}
     }
    };Spacer(Modifier.height(18.dp))
   }
  }
 }
}
@Composable internal fun Panel(content:@Composable ColumnScope.()->Unit){Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=Color.White)){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(9.dp),content=content)}}
@Composable private fun Metric(title:String,value:Double){Column{Text(title);Text("%.2f%%".format(value*100),fontWeight=FontWeight.Bold)}}
@Composable internal fun Lines(data:JSONArray,keys:List<String>){
 Canvas(Modifier.fillMaxWidth().height(160.dp).padding(vertical=12.dp)){
  if(data.length()<2)return@Canvas
  val values=(0 until data.length()).flatMap{i->keys.map{data.getJSONObject(i).getDouble(it)}};val lo=values.min();val range=(values.max()-lo).coerceAtLeast(1e-9)
  for((index,key) in keys.withIndex()){
   val path=Path();for(i in 0 until data.length()){val x=size.width*i/(data.length()-1);val y=size.height*(1-((data.getJSONObject(i).getDouble(key)-lo)/range).toFloat());if(i==0)path.moveTo(x,y)else path.lineTo(x,y)}
   drawPath(path,listOf(Mint,Gold,Color(0xff879ba5))[index%3],style=androidx.compose.ui.graphics.drawscope.Stroke(width=if(index==0)3f else 2f))
  }
  drawLine(Color(0xffdfe6e5),Offset(0f,size.height),Offset(size.width,size.height),1f)
 }
}

