package com.woogong.app

import android.Manifest
import android.app.*
import android.appwidget.AppWidgetManager
import android.content.*
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.concurrent.TimeUnit

class WoogongApp:Application(){
 override fun onCreate(){super.onCreate()
  getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("close","장 마감 결과",NotificationManager.IMPORTANCE_DEFAULT))
  WorkManager.getInstance(this).enqueueUniquePeriodicWork("fallback-sync",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<SyncWorker>(30,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
 }
}
class SyncWorker(c:Context,p:WorkerParameters):Worker(c,p){
 override fun doWork():Result {
  var tradingFailed=false
  if(TradingConnection.url(applicationContext).isNotBlank()){
   try{TradingConnection.call(applicationContext,"status")}catch(_:Exception){tradingFailed=true}
  }
  WoogongWidget.updateAll(applicationContext)
  if(Data.url(applicationContext).isBlank()){
   return try{PortfolioStore.recordDaily(applicationContext);if(tradingFailed)Result.retry() else Result.success()}catch(_:Exception){Result.retry()}
  }
  return try{Data.sync(applicationContext);WoogongWidget.updateAll(applicationContext);if(tradingFailed)Result.retry() else Result.success()}catch(e:Exception){Data.prefs(applicationContext).edit().putString("sync_error","갱신 실패 · 이전 기준일을 확인하세요").apply();WoogongWidget.updateAll(applicationContext);Result.retry()}
 }
}
class PushService:FirebaseMessagingService(){
 override fun onNewToken(token:String){Data.prefs(this).edit().putString("fcm_token",token).apply()}
 override fun onMessageReceived(message:RemoteMessage){
  val intent=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  val note=NotificationCompat.Builder(this,"close").setSmallIcon(com.woogong.app.R.drawable.ic_notification).setContentTitle(message.data["title"]?:getString(R.string.app_name)).setContentText(message.data["body"]?:"장 마감 결과를 확인하세요").setAutoCancel(true).setContentIntent(intent).build()
  if(ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT<33)getSystemService(NotificationManager::class.java).notify(3661,note)
  WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<SyncWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
 }
}
