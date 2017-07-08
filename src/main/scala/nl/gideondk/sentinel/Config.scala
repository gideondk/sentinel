package nl.gideondk.sentinel

import akka.actor.{ ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import com.typesafe.config.{ Config ⇒ TypesafeConfig }

class Config(config: TypesafeConfig) extends Extension {
  val producerParallelism = config.getInt("pipeline.parallelism")
}

object Config extends ExtensionId[Config] with ExtensionIdProvider {
  override def lookup = Config
  override def createExtension(system: ExtendedActorSystem) =
    new Config(system.settings.config.getConfig("nl.gideondk.sentinel"))
  override def get(system: ActorSystem): Config = super.get(system)

  private def config(implicit system: ActorSystem) = apply(system)

  def producerParallelism(implicit system: ActorSystem) = config.producerParallelism
}

