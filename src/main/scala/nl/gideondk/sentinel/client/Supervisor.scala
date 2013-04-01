package nl.gideondk.sentinel.client

import nl.gideondk.sentinel._

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import akka.actor._
import akka.routing._
import akka.event.Logging

import scalaz._
import Scalaz._
import effect._

import akka.util.ByteString
import scala.concurrent.Promise

import scala.reflect.ClassTag

import java.net.InetSocketAddress

trait SentinelClient extends Actor {
  import context.dispatcher

  val log = Logging(context.system, this)

  /* Server host and port to connect to */
  def host: String
  def port: Int
  def address = new InetSocketAddress(host, port)

  /* Router configuration used for Akka Routing */
  def routerConfig: RouterConfig

  /* Client description */
  def description: String

  /* Worker class and description */
  def workerClass: Class[_ <: Actor]
  def workerDescription: String = "SentinelWorker"

  var router: Option[ActorRef] = None
  def reconnectDuration: FiniteDuration

  def routerProto = {
    context.system.actorOf(Props(workerClass).withRouter(routerConfig).withDispatcher("nl.gideondk.sentinel.sentinel-client-worker-dispatcher"))
  }

  def initialize {
    router = Some(routerProto)
    router.get ! Broadcast(ConnectToHost(address))
    context.watch(router.get)
  }

  override def preStart = {
    self ! InitializeRouter
  }

  def genericMessageHandler: Receive = {
    case InitializeRouter ⇒
      initialize

    case ReconnectRouter ⇒
      if (router.isEmpty) initialize

    case Terminated(actor) ⇒
      /* If router died, restart after a period of time */
      router = None
      log.debug("Router died, restarting in: "+reconnectDuration.toString())
      context.system.scheduler.scheduleOnce(reconnectDuration, self, ReconnectRouter)

    case x: SentinelCommand ⇒
      router match {
        case Some(r) ⇒ r forward x
        case None    ⇒ x.promise.failure(NoConnectionException())
      }

    case _ ⇒
  }

  /* Message handler implemented by actor */
  def messageHandler: Receive = Map.empty

  def receive = messageHandler orElse genericMessageHandler
}

object SentinelClient {

  /** Creates a new SentinelClient
   *
   *  @tparam T worker class to be used for the client
   *  @param serverHost the host to connect to (hostname or ip)
   *  @param serverPort the port to connect to
   *  @param clientRouterConfig Akka router configuration to be used to route the worker actors
   *  @param clientDescription description used for logging purposes
   *  @param workerReconnectTime the amount of time a client tries to reconnect after disconnection
   */

  def apply[T <: SentinelClientWorker: ClassTag](serverHost: String, serverPort: Int, clientRouterConfig: RouterConfig, clientDescription: String = "Sentinel Client", workerReconnectTime: FiniteDuration = 2 seconds)(implicit system: ActorSystem) = {
    system.actorOf(Props(new SentinelClient {
      val workerClass = implicitly[ClassTag[T]].runtimeClass.asInstanceOf[Class[_ <: Actor]]
      val description = clientDescription
      val host = serverHost
      val port = serverPort
      val routerConfig = clientRouterConfig
      val reconnectDuration = workerReconnectTime
    }))
  }

  /** Creates a new SentinelClient which routes to workers randomly
   *
   *  @tparam T worker class to be used for the client
   *  @param serverHost the host to connect to (hostname or ip)
   *  @param serverPort the port to connect to
   *  @param numberOfWorkers the amount of worker actors used to connect to the server
   *  @param clientDescription description used for logging purposes
   *  @param workerReconnectTime the amount of time a client tries to reconnect after disconnection
   */
  def randomRouting[T <: SentinelClientWorker: ClassTag](serverHost: String, serverPort: Int, numberOfWorkers: Int, description: String = "Sentinel Client", workerReconnectTime: FiniteDuration = 2 seconds)(implicit system: ActorSystem) =
    apply[T](serverHost, serverPort, RandomRouter(numberOfWorkers), description, workerReconnectTime)

  /** Creates a new SentinelClient which routes to workers in a round robin style
   *
   *  @tparam T worker class to be used for the client
   *  @param serverHost the host to connect to (hostname or ip)
   *  @param serverPort the port to connect to
   *  @param numberOfWorkers the amount of worker actors used to connect to the server
   *  @param clientDescription description used for logging purposes
   *  @param workerReconnectTime the amount of time a client tries to reconnect after disconnection
   */
  def roundRobinRouting[T <: SentinelClientWorker: ClassTag](serverHost: String, serverPort: Int, numberOfWorkers: Int, description: String = "Sentinel Client", workerReconnectTime: FiniteDuration = 2 seconds)(implicit system: ActorSystem) =
    apply[T](serverHost, serverPort, RoundRobinRouter(numberOfWorkers), description, workerReconnectTime)

}

final class AskableSentinelClient(val clientActorRef: ActorRef) extends AnyVal {
  def ??(command: ByteString) = {
    val ioAction = {
      val promise = Promise[Any]()
      clientActorRef ! SentinelCommand(command, promise)
      promise
    }.point[IO]

    ValidatedFutureIO(ioAction.map(x ⇒ ValidatedFuture(x.future)))
  }
}

private case object InitializeRouter

private case object ReconnectRouter

case class NoConnectionException extends Throwable
