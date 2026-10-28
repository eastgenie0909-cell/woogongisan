package com.woogong.app

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Composable internal fun CloseAlertSettings(resumeTick:Int){
 val c=LocalContext.current
 var enabled by remember{mutableStateOf(CloseAlerts.enabled(c))}
 var allowed by remember{mutableStateOf(CloseAlerts.allowed(c))}
 var message by remember{mutableStateOf("")}
 LaunchedEffect(resumeTick){allowed=CloseAlerts.allowed(c)}
 Panel{
  Text("장 마감 알림",fontWeight=FontWeight.Bold)
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("새 모델 결과 알림");Switch(checked=enabled,onCheckedChange={enabled=it;CloseAlerts.enable(c,it)})}
  Text(if(!enabled)"앱 알림 꺼짐" else if(allowed)"휴대폰 알림 허용됨" else "휴대폰에서 우공이산 알림을 허용해주세요.",color=if(enabled&&allowed)Mint else Gold)
  Text("새 미국장 종가 결과를 확인하면 하루·모델별 한 번 알려드립니다. Firebase 등록은 필요하지 않습니다. 백그라운드 조회는 약 30분 간격이며 절전·통신·서버 게시 지연에 따라 늦어질 수 있습니다.",fontSize=12.sp)
  Button(enabled=enabled,onClick={allowed=CloseAlerts.allowed(c);message=if(CloseAlerts.test(c))"테스트 알림을 보냈습니다. 알림창에서 확인하세요." else "휴대폰 알림 설정을 열어 우공이산 알림과 ‘장 마감 결과’ 채널을 허용하세요."}){Text("알림 테스트")}
  TextButton(onClick={c.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,c.packageName))}){Text("휴대폰 알림 설정 열기")}
  val last=Data.prefs(c).getString("close_alert_last_at",null)
  Text(last?.let{"최근 새 결과 알림: $it"}?:"아직 새 결과 알림 기록이 없습니다.",fontSize=11.sp)
  if(message.isNotEmpty())Text(message,fontSize=12.sp)
 }
}
