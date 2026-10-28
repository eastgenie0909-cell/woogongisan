package com.woogong.app

import org.junit.Assert.*
import org.junit.Test

class WidgetPresentationTest {
 @Test fun smallOrShortLaunchersUseCompact(){
  assertEquals(WidgetPresentation.Layout.COMPACT,WidgetPresentation.layout(220,180,1f))
  assertEquals(WidgetPresentation.Layout.COMPACT,WidgetPresentation.layout(350,110,1f))
  assertEquals(WidgetPresentation.Layout.GRAPH,WidgetPresentation.layout(350,160,1f))
 }
 @Test fun enlargedFontsReduceDensity(){
  assertEquals(WidgetPresentation.Layout.COMPACT,WidgetPresentation.layout(350,170,1.5f))
  assertEquals(WidgetPresentation.Layout.GRAPH,WidgetPresentation.layout(350,260,1.5f,WidgetPresentation.Layout.DETAIL))
  assertEquals(WidgetPresentation.Layout.DETAIL,WidgetPresentation.layout(350,330,1.5f,WidgetPresentation.Layout.DETAIL))
 }
 @Test fun detailRequiresEnoughHeight(){
  assertEquals(WidgetPresentation.Layout.GRAPH,WidgetPresentation.layout(350,180,1f,WidgetPresentation.Layout.DETAIL))
  assertEquals(WidgetPresentation.Layout.DETAIL,WidgetPresentation.layout(350,240,1f,WidgetPresentation.Layout.DETAIL))
 }
 @Test fun graphBitmapTracksAvailableAspectRatio(){
  assertTrue(WidgetPresentation.chartPixels(350,110,WidgetPresentation.Layout.COMPACT)<100)
  assertTrue(WidgetPresentation.chartPixels(350,180,WidgetPresentation.Layout.GRAPH)<200)
  assertTrue(WidgetPresentation.chartPixels(350,300,WidgetPresentation.Layout.DETAIL)>300)
  assertEquals(64,WidgetPresentation.chartPixels(500,0,WidgetPresentation.Layout.COMPACT))
 }
 @Test fun periodCyclesThroughAllWindows(){
  var p="1m";val seen=mutableListOf<String>()
  repeat(4){seen.add(p);p=WidgetPresentation.nextPeriod(p)}
  assertEquals(listOf("1m","3m","1y","all"),seen);assertEquals("1m",p)
  assertEquals("1m",WidgetPresentation.nextPeriod("invalid"))
 }
}
