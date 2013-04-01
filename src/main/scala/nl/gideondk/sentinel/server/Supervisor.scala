package nl.gideondk.sentinel.server

import scala.concurrent.duration._

import akka.util.ByteString

import akka.io._
import akka.io.Tcp._
import akka.routing._
import akka.actor._

import akka.actor.IO.IterateeRef
import akka.event.Logging

import scalaz._
import Scalaz._

import java.net.InetSocketAddress

import scala.reflect.ClassTag

trait SentinelServer extends Actor {
  import akka.io.Tcp._
  import context.dispatcher

  val tcp = akka.io.IO(Tcp)(context.system)

  /* Port to which the server should listen to */
  def port: Int
  def address = new InetSocketAddress(port)

  /* Router configuration used for Akka Routing */
  def routerConfig: RouterConfig

  /* Worker class and description */
  def workerClass: Class[_ <: Actor]
  def serverDescription: String

  val reinitTime: FiniteDuration = 1 second

  val log = Logging(context.system, this)
  var router: Option[ActorRef] = None

  def routerProto = {
    context.system.actorOf(Props(workerClass).withRouter(routerConfig).withDispatcher("nl.gideondk.sentinel.sentinel-server-worker-dispatcher"))
  }

  def initialize {
    router = Some(routerProto)
    context.watch(router.get)
  }

  override def preStart = {
    tcp ! Bind(self, address)
    self ! InitializeServerRouter
  }

  def genericMessageHandler: Receive = {
    case InitializeServerRouter ⇒
      initialize

    case ReconnectServerRouter ⇒
      if (router.isEmpty) initialize

    case Terminated(actor) ⇒
      router = None
      log.debug("Router died, restarting in: "+reinitTime.toString())
      context.system.scheduler.scheduleOnce(reinitTime, self, ReconnectServerRouter)

    case Bound ⇒
      log.debug(serverDescription+" bound to "+address)

    case CommandFailed(cmd) ⇒
      cmd match {
        case x: Bind ⇒
          log.error(serverDescription+" failed to bind to "+address)
      }

    case req @ Connected(remoteAddr, localAddr) ⇒
      router.get forward req
  }

  def messageHandler: Receive = Map.empty

  def receive = messageHandler orElse genericMessageHandler
}

object SentinelServer {

  /** Creates a new SentinelServer
   *
   *  @tparam T worker class to be used for the server
   *  @param serverPort the port to host on
   *  @param serverRouterConfig Akka router configuration to be used to route the worker actors
   *  @param description description used for logging purposes
   */

  def apply[T <: SentinelServerWorker: ClassTag](serverPort: Int, serverRouterConfig: RouterConfig, description: String = "Sentinel Server")(implicit system: ActorSystem) = {
    system.actorOf(Props(new SentinelServer {
      val workerClass = implicitly[ClassTag[T]].runtimeClass.asInstanceOf[Class[_ <: Actor]]
      val serverDescription = description
      val port = serverPort
      val routerConfig = serverRouterConfig
    }))
  }

  /** Creates a new SentinelServer which routes to workers randomly
   *
   *  @tparam T worker class to be used for the server
   *  @param serverPort the port to host on
   *  @param numberOfWorkers the amount of worker actors used to represent the server
   *  @param description description used for logging purposes
   */

  def randomRouting[T <: SentinelServerWorker: ClassTag](serverPort: Int, numberOfWorkers: Int, description: String = "Sentinel Server")(implicit system: ActorSystem) =
    apply[T](serverPort, RandomRouter(numberOfWorkers), description)

  /** Creates a new SentinelServer which to workers in a round robin style
   *
   *  @tparam T worker class to be used for the server
   *  @param serverPort the port to host on
   *  @param numberOfWorkers the amount of worker actors used to represent the server
   *  @param description description used for logging purposes
   */

  def roundRobinRouting[T <: SentinelServerWorker: ClassTag](serverPort: Int, numberOfWorkers: Int, description: String = "Sentinel Server")(implicit system: ActorSystem) =
    apply[T](serverPort, RoundRobinRouter(numberOfWorkers), description)

}

private case object InitializeServerRouter
private case object ReconnectServerRouter

case class NoWorkerException extends Throwable
