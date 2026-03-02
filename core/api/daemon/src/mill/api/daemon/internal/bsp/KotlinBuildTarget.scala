package mill.api.daemon.internal.bsp

/** Mill specific BSP build target data for Kotlin/JVM modules. */
case class KotlinBuildTarget(
    /** The Kotlin language version used for this target (e.g. "2.1"). */
    languageVersion: String,
    /** The Kotlin API version used for this target (e.g. "2.1"). */
    apiVersion: String,
    /** The effective kotlinc options used to compile this target. */
    kotlincOptions: Seq[String],
    /** Classpath entries for friends of this target. */
    associates: Seq[String],
    /** The JVM build target describing JDK used. */
    jvmBuildTarget: Option[JvmBuildTarget]
)

object KotlinBuildTarget {
  val dataKind: String = "kotlin"
}

