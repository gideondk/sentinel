package nl.gideondk.sentinel.server

import akka.actor._
import akka.io._
import akka.util.ByteString

import nl.gideondk.sentinel.SentinelResolver

object SentinelServer {
  def apply[Evt, Cmd](serverPort: Int, resolver: SentinelResolver[Evt, Cmd], description: String = "Sentinel Server")(stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString], lowBytes: Long = 100L, highBytes: Long = 50 * 1024L, maxBufferSize: Long = 1000L * 1024L)(implicit system: ActorSystem): ActorRef = {
    system.actorOf(Props(new ServerCore(serverPort, description, stages, resolver)(lowBytes, highBytes, maxBufferSize)).withDispatcher("nl.gideondk.sentinel.sentinel-dispatcher"))
  }
}
