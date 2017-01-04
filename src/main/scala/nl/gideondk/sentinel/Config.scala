package nl.gideondk.sentinel

import com.typesafe.config.ConfigFactory

object Config {
  private lazy val config = ConfigFactory.load().getConfig("nl.gideondk.sentinel")

  val producerParallelism = config.getInt("pipeline.parallelism")
}
