package com.woogong.app
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
class TimingFreshnessTest {
 private val now=Instant.parse("2026-10-26T15:00:00Z")
 @Test fun expiryIsExclusive(){
  assertTrue(timingFresh(true,true,now.plusSeconds(1).toString(),now))
  assertFalse(timingFresh(true,true,now.toString(),now))
  assertFalse(timingFresh(true,true,now.minusSeconds(1).toString(),now))
 }
 @Test fun errorsAndMissingExpiryNeverActivate(){
  assertFalse(timingFresh(false,true,now.plusSeconds(1).toString(),now))
  assertFalse(timingFresh(true,false,now.plusSeconds(1).toString(),now))
  assertFalse(timingFresh(true,true,"",now))
 }
}
