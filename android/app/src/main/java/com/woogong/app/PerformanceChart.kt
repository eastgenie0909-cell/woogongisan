package com.woogong.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

internal val PerformanceColors=listOf(Color(0xff75dfc5),Color(0xff8caaff),Color(0xffe5bb70))
@Composable internal fun PerformanceChart(window:PerformanceWindow){
 Canvas(Modifier.fillMaxWidth().height(220.dp)){
  val left=48.dp.toPx();val top=12.dp.toPx();val bottom=size.height-24.dp.toPx();val width=size.width-left-5.dp.toPx();val height=bottom-top
  val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(162,181,201);textSize=10.dp.toPx()}
  fun y(v:Double)=top+height*(1-((v-window.low)/window.span).toFloat())
  listOf(window.low,window.low+window.span/2,window.low+window.span).forEach{v->
   val yy=y(v);drawLine(Color(0xff304153),Offset(left,yy),Offset(size.width,yy),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(5f,7f)))
   drawContext.canvas.nativeCanvas.drawText("%+.1f%%".format(v*100),0f,yy+paint.textSize/3,paint)
  }
  drawLine(Color(0xff66778a),Offset(left,y(0.0)),Offset(size.width,y(0.0)),1.dp.toPx())
  listOf("model","tqqq","brkb").forEachIndexed{index,key->
   val path=Path();window.points.forEachIndexed{i,p->val xx=left+width*i/(window.points.size-1);if(i==0)path.moveTo(xx,y(p.value(key))) else path.lineTo(xx,y(p.value(key)))}
   drawPath(path,PerformanceColors[index],style=Stroke(width=if(index==0)3.dp.toPx() else 2.dp.toPx()))
  }
  val yy=size.height-4.dp.toPx();drawContext.canvas.nativeCanvas.drawText(window.start,left,yy,paint)
  val endWidth=paint.measureText(window.end);drawContext.canvas.nativeCanvas.drawText(window.end,size.width-endWidth,yy,paint)
 }
}
