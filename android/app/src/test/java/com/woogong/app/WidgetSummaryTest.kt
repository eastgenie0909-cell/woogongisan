package com.woogong.app
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
class WidgetSummaryTest {
 @Test fun oldAndFutureValuesAreStale(){
  val now=Instant.parse("2026-10-26T00:00:00Z")
  assertFalse(WidgetSummary.expired(now.minusSeconds(300),now))
  assertTrue(WidgetSummary.expired(now.minusSeconds(3601),now))
  assertTrue(WidgetSummary.expired(now.plusSeconds(1),now))
 }
}
