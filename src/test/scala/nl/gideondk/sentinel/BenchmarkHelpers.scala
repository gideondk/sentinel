package nl.gideondk.sentinel

object BenchmarkHelpers {
  def timed(desc: String, n: Int)(benchmark: ⇒ Unit) = {
    println("* "+desc)
    val t = System.currentTimeMillis
    benchmark
    val d = System.currentTimeMillis - t

    println("* - number of ops/s: "+n / (d / 1000.0)+"\n")
  }
}