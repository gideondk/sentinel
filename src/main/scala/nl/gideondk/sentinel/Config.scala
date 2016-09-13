package nl.gideondk.sentinel

import com.typesafe.config.ConfigFactory

object Config {
  private lazy val config = ConfigFactory.load().getConfig("sentinel")

  val parallelism = config.getInt("pipeline.parallelism")
  val framesize = config.getInt("pipeline.framesize")
  val buffersize = config.getInt("pipeline.buffersize")
}
