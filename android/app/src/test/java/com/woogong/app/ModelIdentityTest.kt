package com.woogong.app

import org.junit.Assert.*
import org.junit.Test

class ModelIdentityTest {
 private val current=ModelIdentity(1,"woogong-1.3-d","a".repeat(64))
 @Test fun acceptsSameModel() {assertTrue(current.matches(current.copy()))}
 @Test fun rejectsOldCachedModel() {assertFalse(current.matches(current.copy(modelId="woogong-v1")))}
 @Test fun rejectsChangedRulesOnSameDate() {assertFalse(current.matches(current.copy(fingerprint="b".repeat(64))))}
 @Test fun rejectsUnknownSchema() {assertFalse(current.matches(current.copy(schema=2)))}
 @Test fun rejectsMissingIdentity() {assertFalse(current.copy(modelId="").matches(current.copy(modelId="")))}
 @Test fun rejectsMalformedFingerprint() {
  val invalid=current.copy(fingerprint="x".repeat(64))
  assertFalse(invalid.matches(invalid))
 }
}
