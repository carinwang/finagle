package com.twitter.finagle.netty4.http

import com.twitter.concurrent.AsyncQueue
import com.twitter.conversions.DurationOps._
import com.twitter.finagle.Status
import com.twitter.finagle.netty4.ByteBufConversion
import com.twitter.finagle.netty4.transport.ChannelTransport
import com.twitter.finagle.transport.{
  QueueTransport,
  SimpleTransportContext,
  Transport,
  TransportContext
}
import com.twitter.io.ReaderDiscardedException
import com.twitter.io.{Buf, Pipe, Reader}
import com.twitter.util._
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.{
  DefaultHttpContent,
  DefaultLastHttpContent,
  HttpContent,
  LastHttpContent
}
import io.netty.handler.codec.{http => NettyHttp}
import java.nio.charset.StandardCharsets.UTF_8
import org.scalatest.FunSuite
import scala.language.reflectiveCalls

class StreamTransportsTest extends FunSuite {
  import StreamTransports._

  test("readChunk: returned bufs have same content as http chunk") {
    val input = Array[Byte](1, 2, 3)
    val output = readChunk(new DefaultHttpContent(Unpooled.wrappedBuffer(input)))
    assert(output == Buf.ByteArray.Owned(input))
  }

  test("readChunk: reads empty http chunk as Buf.Empty") {
    val output = readChunk(new NettyHttp.DefaultLastHttpContent)
    assert(output == Buf.Empty)
  }

  test("chunkOfBuf: wraps buf in http chunk") {
    val input = Array[Byte](1, 2, 3)
    val chunk = chunkOfBuf(Buf.ByteArray.Owned(input))

    val output = new Array[Byte](chunk.content.readableBytes)
    chunk.content.readBytes(output)
    assert(input.toSeq == output.toSeq)
  }

  test("streamChunks: streams http chunks into transport") {
    val rw = new Pipe[Buf]()

    val (write, read) = (new AsyncQueue[Any], new AsyncQueue[Any])
    val tr = new QueueTransport[Any, Any](write, read)

    rw.write(Buf.Utf8("msg1"))

    val chunk1F = write.poll()

    streamChunks(tr, rw)

    val chunk =
      Await.result(chunk1F, 2.seconds).asInstanceOf[NettyHttp.HttpContent]

    assert(chunk.content.toString(UTF_8) == "msg1")

    val chunkF = write.poll()

    rw.write(Buf.Utf8("msg2"))

    val chunk2 = Await.result(chunkF, 2.seconds).asInstanceOf[NettyHttp.HttpContent]
    assert(chunk2.content.toString(UTF_8) == "msg2")

    Await.ready(rw.close(), 2.seconds)

    val lastChunk = Await.result(write.poll(), 2.seconds).asInstanceOf[NettyHttp.HttpContent]

    assert(lastChunk.isInstanceOf[NettyHttp.LastHttpContent])
  }

  test("can collate a HttpContent stream") {
    val (write, read) = (new AsyncQueue[Any], new AsyncQueue[Any])
    val tr = new QueueTransport[Any, Any](write, read)
    val coll = collate(tr, readChunk)(_.isInstanceOf[LastHttpContent])

    val bytes: Array[Byte] = (1 to 10).map(_.toByte).toArray
    read.offer(new DefaultHttpContent(io.netty.buffer.Unpooled.wrappedBuffer(bytes)))
    read.offer(new DefaultLastHttpContent)

    val content = Await.result(Reader.readAll(coll), 2.seconds)
    assert(Buf.ByteArray.Owned.extract(content).toList == (1 to 10).toList)
  }

  test("can collate a HttpContent stream that is terminated by a non-empty chunk") {
    val (write, read) = (new AsyncQueue[Any], new AsyncQueue[Any])
    val tr = new QueueTransport[Any, Any](write, read)
    val coll = collate(tr, readChunk)(_.isInstanceOf[LastHttpContent])

    val bytes: Array[Byte] = (1 to 10).map(_.toByte).toArray
    val moreBytes: Array[Byte] = (11 to 20).map(_.toByte).toArray
    read.offer(new DefaultHttpContent(io.netty.buffer.Unpooled.wrappedBuffer(bytes)))
    read.offer(new DefaultLastHttpContent(io.netty.buffer.Unpooled.wrappedBuffer(moreBytes)))

    val content = Await.result(Reader.readAll(coll), 2.seconds)
    assert(Buf.ByteArray.Owned.extract(content).toList == (1 to 20))
  }

  def tmpReadChunk(chunk: Any): Future[Option[Buf]] = chunk match {
    case chunk: LastHttpContent =>
      Future.None

    case chunk: HttpContent =>
      Future.value(Some(ByteBufConversion.byteBufAsBuf(chunk.content.duplicate)))
  }

  test("eof satisfies collated reader") {
    val channel: EmbeddedChannel = new EmbeddedChannel()
    val chanTran = Transport.cast[Any, HttpContent](new ChannelTransport(channel))
    val coll: Reader[Buf] with Future[Unit] =
      collate(chanTran, readChunk)(_.isInstanceOf[LastHttpContent])
    val read = coll.read()

    val bytes: Array[Byte] = (1 to 10).map(_.toByte).toArray
    channel.writeInbound(new DefaultHttpContent(io.netty.buffer.Unpooled.wrappedBuffer(bytes)))

    val content = Await.result(read, 2.seconds).get
    assert(Buf.ByteArray.Owned.extract(content).toList == (1 to 10))
    channel.writeInbound(new DefaultLastHttpContent)
    Await.ready(coll, 2.seconds)
  }

  val failingT = new Transport[Any, Any] {
    type Context = TransportContext

    def write(req: Any): Future[Unit] = Future.exception(new Exception("nop"))

    def status: Status = ???

    def read(): Future[Any] = ???

    val onClose: Future[Throwable] = Future.exception(new Exception)

    def close(deadline: Time): Future[Unit] = ???

    val context: TransportContext = new SimpleTransportContext()
  }

  test("streamChunks: discard reader on transport write failure") {
    val rw = new Pipe[Buf]
    rw.write(Buf.Utf8("msg"))

    streamChunks(failingT, rw)

    intercept[ReaderDiscardedException] { Await.result(rw.read()) }
  }

  trait Collate {
    val writeq = new AsyncQueue[String]
    val readq = new AsyncQueue[String]
    val trans = new QueueTransport(writeq, readq)
    val fail = new Exception("fail")
    def read(string: String): Buf = string match {
      case "eof" => Buf.Empty
      case x => Buf.Utf8(x)
    }
    val coll = collate(trans, read)(_ == "eof")
    assert(!coll.isDefined)

    def assertDiscarded(f: Future[_]): Unit = {
      assert(f.isDefined)
      intercept[ReaderDiscardedException] { Await.result(f, 2.seconds) }
    }
  }

  test("collate: read through") {
    val c = new Collate {}
    // Long read
    val r1 = c.coll.read()
    assert(!r1.isDefined)
    c.readq.offer("hello")
    c.readq.offer("world")

    assert(Await.result(r1, 2.seconds) == Some(Buf.Utf8("hello")))
    assert(!c.coll.isDefined)

    c.readq.offer("eof")
    // Now, the EOF; but this isn't propagated until the buffered bytes are read.
    assert(!c.coll.isDefined)

    val r2 = c.coll.read()
    assert(r2.isDefined)
    assert(Await.result(r2, 2.seconds) == Some(Buf.Utf8("world")))

    assert(c.coll.isDefined)
    Await.result(c.coll, 2.seconds) // no exceptions

    // Further reads are EOF
    val r4 = Await.result(c.coll.read(), 2.seconds)
    assert(r4 == None)
  }

  test("collate: discard while reading")(new Collate {
    val trans1 = new Transport[String, String] {
      type Context = TransportContext

      val p = new Promise[String]
      var theIntr: Throwable = null
      p.setInterruptHandler {
        case intr =>
          theIntr = intr
      }
      def write(s: String) = ???
      def read() = p
      def status = ???
      val onClose = Future.never
      def close(deadline: Time) = ???
      val context: TransportContext = new SimpleTransportContext()
    }

    val coll1 = collate[String](trans1, read)(_ == "eof")
    val r1 = coll1.read()
    assert(!r1.isDefined)

    assert(trans1.theIntr == null)
    coll1.discard()
    assertDiscarded(r1)

    assert(!coll1.isDefined)
    assert(trans1.theIntr != null)
    assert(trans1.theIntr.isInstanceOf[ReaderDiscardedException])

    // This is what a typical transport will do.
    trans1.p.setException(trans1.theIntr)
    assertDiscarded(coll1)
  })

  test("collate: discard while idle")(new Collate {
    readq.offer("hello")
    coll.discard()
    assertDiscarded(coll)
    assertDiscarded(coll.read())
  })
}
