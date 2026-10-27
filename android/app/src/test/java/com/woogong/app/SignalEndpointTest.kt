package com.woogong.app
import org.junit.Assert.*
import org.junit.Test
class SignalEndpointTest {
 @Test fun repositoryUrlConvertsToPages(){assertEquals("https://eastgenie0909-cell.github.io/woogongisan",SignalEndpoint.normalize("https://github.com/eastgenie0909-cell/woogongisan/"))}
 @Test fun pagesSubdirectoryIsPreserved(){assertEquals("https://eastgenie0909-cell.github.io/woogongisan",SignalEndpoint.normalize(" https://eastgenie0909-cell.github.io/woogongisan/ "))}
 @Test fun credentialsAndNonHttpsCannotBeSaved(){for(url in listOf("http://example.com","https://user:secret@example.com","https://example.com?token=secret","https://example.com#secret")){try{SignalEndpoint.normalize(url);fail(url)}catch(_:IllegalArgumentException){}}}
}
