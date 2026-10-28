package com.woogong.app

import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.graphics.*
import android.widget.RemoteViews

class WoogongWidget:AppWidgetProvider(){
 override fun onUpdate(c:Context,m:AppWidgetManager,ids:IntArray){ids.forEach{update(c,m,it)}}
 companion object {
  fun updateAll(c:Context){val m=AppWidgetManager.getInstance(c);m.getAppWidgetIds(ComponentName(c,WoogongWidget::class.java)).forEach{update(c,m,it)}}
  fun update(c:Context,m:AppWidgetManager,id:Int){
   val s=Data.load(c,"snapshot");val status=Data.load(c,"status");val stale=Data.stale(s,status)||Data.prefs(c).getString("sync_error","")!!.isNotEmpty()
   val period=Data.prefs(c).getString("performance_period","1m")?:"1m"
   val window=Performance.read(s,period)
   val v=RemoteViews(c.packageName,R.layout.widget)
   v.setTextViewText(R.id.title,"우공이산 · "+(Performance.periods[period]?:"1개월")+" 비교")
   v.setTextViewText(R.id.account,"우공 "+(window?.let{Performance.percent(it.last.model)}?:"—"))
   v.setTextViewText(R.id.weights,"TQQQ "+(window?.let{Performance.percent(it.last.tqqq)}?:"—"))
   v.setTextViewText(R.id.loan,"버크셔 "+(window?.let{Performance.percent(it.last.brkb)}?:"—"))
   v.setTextViewText(R.id.state,if(window==null)"앱에서 최신 결과를 확인하세요" else "${window.start} ~ ${window.end} · USD · 모델 시뮬레이션"+(if(stale)" · 갱신 필요" else ""))
   v.setImageViewBitmap(R.id.chart,chart(window))
   val intent=PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
   v.setOnClickPendingIntent(R.id.widget_root,intent)
   m.updateAppWidget(id,v)
  }
  internal fun chart(window:PerformanceWindow?):Bitmap {
   val bitmap=Bitmap.createBitmap(800,230,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap)
   val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(162,181,201);textSize=24f}
   if(window==null){canvas.drawText("모델 · TQQQ · 버크셔 B",18f,90f,paint);canvas.drawText("비교 자료 갱신 대기",18f,145f,paint);return bitmap}
   val left=86f;val top=18f;val width=704f;val height=190f
   fun y(v:Double)=top+height*(1-((v-window.low)/window.span).toFloat())
   for(value in listOf(window.low,window.low+window.span/2,window.low+window.span)){
    val yy=y(value);paint.color=Color.rgb(162,181,201);paint.textSize=22f;canvas.drawText("%+.1f%%".format(value*100),0f,yy+7f,paint)
    paint.color=Color.rgb(48,65,83);paint.strokeWidth=1f;canvas.drawLine(left,yy,790f,yy,paint)
   }
   paint.color=Color.rgb(102,119,138);canvas.drawLine(left,y(0.0),790f,y(0.0),paint)
   listOf("model" to Color.rgb(117,223,197),"tqqq" to Color.rgb(140,170,255),"brkb" to Color.rgb(229,187,112)).forEachIndexed{index,(key,color)->
    val path=Path();window.points.forEachIndexed{i,p->val xx=left+width*i/(window.points.size-1);if(i==0)path.moveTo(xx,y(p.value(key))) else path.lineTo(xx,y(p.value(key)))}
    val line=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;strokeWidth=if(index==0)5f else 3.5f;style=Paint.Style.STROKE;strokeJoin=Paint.Join.ROUND}
    canvas.drawPath(path,line)
   }
   return bitmap
  }
 }
}
