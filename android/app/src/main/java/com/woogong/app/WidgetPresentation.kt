package com.woogong.app

/** Launcher sizes are available content dp, not physical pixels or grid cells. */
internal object WidgetPresentation {
 enum class Layout { COMPACT, GRAPH, DETAIL }
 fun layout(width:Int,height:Int,fontScale:Float,preferred:Layout=Layout.GRAPH):Layout {
  val scale=fontScale.coerceAtLeast(1f)
  if(width<270||height<145*scale)return Layout.COMPACT
  if(preferred==Layout.DETAIL&&height>=215*scale)return Layout.DETAIL
  return Layout.GRAPH
 }
 fun chartPixels(width:Int,height:Int,layout:Layout):Int {
  val reserved=when(layout){Layout.COMPACT->88;Layout.GRAPH->116;Layout.DETAIL->158}
  val padding=if(layout==Layout.COMPACT)16 else 28
  return (((height-reserved).coerceAtLeast(20).toDouble()/(width-padding).coerceAtLeast(100))*800).toInt().coerceIn(64,900)
 }
 fun nextPeriod(period:String)=when(period){"1m"->"3m";"3m"->"1y";"1y"->"all";else->"1m"}
}
