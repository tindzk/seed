package seed.publish.util

import java.net.URI

import org.apache.commons.io.IOUtils
import org.apache.http.{HttpHost, HttpRequest, HttpRequestInterceptor}
import org.apache.http.entity.ContentType
import seed.util.ZioHelpers._
import zio.Task
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.client.protocol.HttpClientContext
import org.apache.http.impl.auth.BasicScheme
import org.apache.http.impl.client.{BasicAuthCache, BasicCredentialsProvider}
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient
import org.apache.http.impl.nio.client.HttpAsyncClients
import org.apache.http.nio.client.methods.HttpAsyncMethods
import org.apache.http.nio.protocol.HttpAsyncRequestProducer
import org.apache.http.protocol.HttpContext

class Http(httpClient: CloseableHttpAsyncClient) {
  def put(url: String, bytes: Array[Byte]): Task[String] = {
    val producer =
      HttpAsyncMethods.createPut(url, bytes, ContentType.DEFAULT_BINARY)
    send(url, producer)
  }

  def post(url: String, bytes: Array[Byte]): Task[String] = {
    val producer =
      HttpAsyncMethods.createPost(url, bytes, ContentType.DEFAULT_BINARY)
    send(url, producer)
  }

  def destroy(): Unit = httpClient.close()

  private def send(url: String, producer: HttpAsyncRequestProducer) = {
    val client = new CompletableHttpAsyncClient(httpClient)

    val uri        = URI.create(url)
    val targetHost = new HttpHost(uri.getHost, uri.getPort, uri.getScheme)
    val authCache  = new BasicAuthCache()
    authCache.put(targetHost, new BasicScheme())

    val clientContext = HttpClientContext.create()
    clientContext.setAuthCache(authCache)

    val future =
      client.execute(producer, HttpAsyncMethods.createConsumer(), clientContext)

    fromCompletableFuture(future)
      .map(r => IOUtils.toString(r.getEntity.getContent, "UTF-8"))
  }
}

class CustomRequestInterceptor(log: seed.Log) extends HttpRequestInterceptor {
  override def process(request: HttpRequest, context: HttpContext): Unit =
    log.debug("Sending HTTP request " + request + "...")
}

object Http {
  def create(log: seed.Log, authHost: String, auth: (String, String)): Http = {
    val credsProvider = new BasicCredentialsProvider()
    credsProvider.setCredentials(
      new AuthScope(authHost, 443),
      new UsernamePasswordCredentials(auth._1, auth._2)
    )

    val c = HttpAsyncClients
      .custom()
      .setDefaultCredentialsProvider(credsProvider)
      .addInterceptorFirst(new CustomRequestInterceptor(log))
      .build()
    c.start()

    new Http(c)
  }
}
