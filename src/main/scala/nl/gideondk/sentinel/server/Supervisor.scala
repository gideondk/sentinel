package nl.gideondk.sentinel.server

import nl.gideondk.sentinel.Action
import akka.io.PipelineStage
import akka.actor.ActorRef
import akka.util.ByteString
import akka.io.PipelineContext
import akka.actor.ActorSystem
import akka.actor.Props

object SentinelServer {
  /** Creates a new SentinelServer
   *
   *  @tparam Evt event type (type of requests send to server)
   *  @tparam Cmd command type (type of responses to client)
   *  @tparam Context context type used in pipeline
   *  @param serverPort the port to host on
   *  @param serverRouterConfig Akka router configuration to be used to route the worker actors
   *  @param description description used for logging purposes
   *  @param pipelineCtx the context of type Context used in the pipeline
   *  @param stages the stages used within the pipeline
   *  @param useWriteAck whether to use ack-based flow control or not
   *  @return a new sentinel server, hosting on the defined port
   */

  def apply[Evt, Cmd](serverPort: Int, decider: Action.Decider[Evt, Cmd], description: String = "Sentinel Server")(stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString], lowBytes: Long = 100L, highBytes: Long = 50 * 1024L, maxBufferSize: Long = 1000L * 1024L)(implicit system: ActorSystem): ActorRef = {
    system.actorOf(Props(new ServerCore(serverPort, description, stages, decider)(lowBytes, highBytes, maxBufferSize)))
  }
}
