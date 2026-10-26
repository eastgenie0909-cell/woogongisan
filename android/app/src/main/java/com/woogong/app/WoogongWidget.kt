package com.woogong.app

import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.graphics.*
import android.widget.RemoteViews
import org.json.JSONObject

class WoogongWidget:AppWidgetProvider(){
 override fun onUpdate(c:Context,m:AppWidgetManager,ids:IntArray){ids.forEach{update(c,m,it)}}
 companion object {
  fun updateAll(c:Context){val m=AppWidgetManager.getInstance(c);m.getAppWidgetIds(ComponentName(c,WoogongWidget::class.java)).forEach{update(c,m,it)}}
  fun update(c:Context,m:AppWidgetManager,id:Int){
   val s=Data.load(c,"snapshot");val status=Data.load(c,"status");val stale=Data.stale(s,status)||Data.prefs(c).getString("sync_error","")!!.isNotEmpty()
   val v=RemoteViews(c.packageName,R.layout.widget)
   v.setTextViewText(R.id.title,c.getString(R.string.app_name)+" · "+s.optString("market_date"))
   v.setTextViewText(R.id.state,if(stale)"과거/갱신 대기 · 새 매매 신호 아님" else s.optString("state_label"))
   v.setTextViewText(R.id.weights,(if(stale)"과거 목표 " else "목표 ")+Data.allocation(s));v.setImageViewBitmap(R.id.chart,comparisonPending())
   val connected=TradingConnection.url(c).isNotBlank()
   val cached=try{JSONObject(Data.prefs(c).getString("trading_widget","{}")!!)}catch(_:Exception){JSONObject()}
   val returns=ReturnsSummary.read(c)
   v.setTextViewText(R.id.account,returns.first)
   v.setTextViewText(R.id.state,returns.second)
   val loan=if(connected)cached.optJSONObject("loan_timing") else s.optJSONObject("loan_timing")?.let{JSONObject(it.toString()).put("valid_until",s.optString("valid_until"))}
   val timing=WidgetSummary.timing(loan,if(connected)loan?.optBoolean("available")==true&&!Data.prefs(c).getBoolean("trading_sync_failed",false) else !stale)
   v.setTextViewText(R.id.loan,if(timing.contains("판정 보류"))"차입 · 보류" else "차입 · 앱에서 확인")
   v.setOnClickPendingIntent(R.id.title,PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
   v.setOnClickPendingIntent(R.id.widget_root,PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
   m.updateAppWidget(id,v)
  }
  fun comparisonPending():Bitmap {
   val bitmap=Bitmap.createBitmap(500,180,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap)
   val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(162,181,201);textSize=27f}
   canvas.drawText("내 계좌 / TQQQ",12f,44f,paint)
   paint.textSize=23f;canvas.drawText("비교 자료 준비 중",12f,105f,paint)
   return bitmap
  }
  fun chart(s:JSONObject):Bitmap {
   val bitmap=Bitmap.createBitmap(800,180,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);val a=s.getJSONObject("charts").getJSONArray("relative")
   if(a.length()<2)return bitmap
   val values=(0 until a.length()).flatMap{listOf(a.getJSONObject(it).getDouble("relative_fast"),a.getJSONObject(it).getDouble("relative_slow"))}
   val lo=values.min();val range=(values.max()-lo).coerceAtLeast(1e-9)
   for((key,color) in listOf("relative_fast" to Color.rgb(103,214,189),"relative_slow" to Color.rgb(232,185,92))){
    val path=Path();for(i in 0 until a.length()){val x=8f+784f*i/(a.length()-1);val y=168f-156f*((a.getJSONObject(i).getDouble(key)-lo)/range).toFloat();if(i==0)path.moveTo(x,y)else path.lineTo(x,y)}
    canvas.drawPath(path,Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;strokeWidth=4f;style=Paint.Style.STROKE})
   };return bitmap
  }
 }
}
