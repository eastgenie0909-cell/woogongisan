package com.woogong.app
import org.junit.Assert.*
import org.junit.Test
class ReturnsSummaryTest {
 @Test fun totalReturn(){assertEquals(25.0,ReturnsSummary.percent(125.0,100.0)!!,1e-8);assertEquals(-100.0,ReturnsSummary.percent(0.0,100.0)!!,1e-8)}
 @Test fun missingCapitalIsNotZeroReturn(){assertNull(ReturnsSummary.percent(100.0,0.0));assertNull(ReturnsSummary.percent(100.0,-1.0));assertNull(ReturnsSummary.percent(Double.NaN,100.0))}
}
