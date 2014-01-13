package blaze.pipeline
package stages

import java.nio.channels.{AsynchronousSocketChannel => NioChannel,
                          ClosedChannelException,
                          ShutdownChannelGroupException,
                          CompletionHandler}

import java.nio.ByteBuffer
import scala.concurrent.{Promise, Future}
import Command._
import java.io.IOException
import java.util.Date
import java.util.concurrent.TimeUnit

import java.lang.{Long => JLong}
import scala.annotation.tailrec


/**
* @author Bryce Anderson
*         Created on 1/4/14
*/
class ByteBufferHead(channel: NioChannel,
                     val name: String = "ByteBufferHeadStage",
                     bufferSize: Int = 20*1024) extends HeadStage[ByteBuffer] {

  private val bytes = ByteBuffer.allocate(bufferSize)

  def writeRequest(data: ByteBuffer): Future[Unit] = {

    if (!data.hasRemaining() && data.position > 0) {
      logger.warn("Received write request with non-zero position but ZERO available" +
                 s"bytes at ${new Date} on blaze.channel $channel: $data, head: $next")
      return Future.successful()
    }

    val f = Promise[Unit]

    def go(i: Int) {
      channel.write(data, null: Null, new CompletionHandler[Integer, Null] {
        def failed(exc: Throwable, attachment: Null) {
          if (exc.isInstanceOf[ClosedChannelException]) logger.trace("Channel closed, dropping packet")
          else logger.error("Failure writing to channel", exc)

          f.tryFailure(exc)
        }

        def completed(result: Integer, attachment: Null) {
          if (result.intValue < i) go(i - result.intValue)  // try to write again
          else f.trySuccess()      // All done
        }
      })
    }
    go(data.remaining())

    f.future
  }


  override def writeRequest(data: Seq[ByteBuffer]): Future[Unit] = {

    val f = Promise[Unit]
    val srcs = data.toArray
    val sz: Long = {
      @tailrec def go(size: Long, pos: Int): Long = {
        if (pos < srcs.length) go(size + srcs(pos).remaining(), pos + 1)
        else size
      }
      go(0, 0)
    }

    def go(i: Long): Unit = {
      channel.write[Null](srcs, 0, srcs.length, -1L, TimeUnit.MILLISECONDS, null: Null, new CompletionHandler[JLong, Null] {
        def failed(exc: Throwable, attachment: Null) {
          if (exc.isInstanceOf[ClosedChannelException]) logger.trace("Channel closed, dropping packet")
          else logger.error("Failure writing to channel", exc)

          f.tryFailure(exc)
        }

        def completed(result: JLong, attachment: Null) {
          if (result.longValue < i) go(i - result.longValue)  // try to write again
          else f.trySuccess()      // All done
        }
      })
    }
    go(sz)

    f.future
  }

  def readRequest(size: Int): Future[ByteBuffer] = {
      
    val p = Promise[ByteBuffer]

    bytes.clear()

    if (size >= 0 && size + bytes.position() < bufferSize)
      bytes.limit(size + bytes.position())

    channel.read(bytes, null: Null, new CompletionHandler[Integer, Null] {
      def failed(exc: Throwable, attachment: Null): Unit = {
        exc match {
          case e: IOException =>
            logger.trace("Channel IO Error. Closing", e)
            channelShutdown()
            p.tryFailure(EOF)

          case e: ShutdownChannelGroupException =>
            logger.trace("Channel Group was shutdown", e)
            channelShutdown()
            p.tryFailure(EOF)

          case e: Throwable =>  // Don't know what to do besides close
            channelError(e)
            p.tryFailure(e)
        }
      }

      def completed(i: Integer, attachment: Null) {
        if (i.intValue() >= 0) {
          bytes.flip()
          p.trySuccess(bytes)
        } else {   // must be end of stream
          p.tryFailure(EOF)
          channelShutdown()
        }
      }
    })
    
    p.future
  }

  override def stageShutdown(): Unit = closeChannel()

  private def channelShutdown() {
    closeChannel()
    sendInboundCommand(Shutdown)
  }

  private def channelError(e: Throwable) {
    logger.error("Unexpected fatal error", e)
    sendInboundCommand(Error(e))
    channelShutdown()
  }

  private def closeChannel() {
    logger.trace("channelClose")
    try channel.close()
    catch {  case e: IOException => /* Don't care */ }
  }

  override def outboundCommand(cmd: Command): Unit = cmd match {
    case Shutdown         => closeChannel()
    case Error(e)         => logger.error("ByteBufferHead received error command", e); channelError(e)
    case cmd              => // NOOP
  }
}
