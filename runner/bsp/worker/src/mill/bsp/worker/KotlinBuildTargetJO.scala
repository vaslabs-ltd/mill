package mill.bsp.worker

import mill.api.daemon.internal.bsp.KotlinBuildTarget
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.JvmBuildTarget
import scala.jdk.CollectionConverters.*
import mill.bsp.worker.Utils.jvmBuildTarget

// https://github.com/JetBrains/intellij-bsp/blob/main/protocol/src/main/kotlin/org/jetbrains/bsp/protocol/KotlinBuildTarget.kt
case class KotlinBuildTargetJO(
    languageVersion: String,
    apiVersion: String,
    kotlincOptions: java.util.List[String],
    associates: java.util.List[BuildTargetIdentifier],
    jvmBuildTarget: JvmBuildTarget
                              )

object KotlinBuildTargetJO {
  def fromKotlinBuildTarget(kbt: KotlinBuildTarget): KotlinBuildTargetJO = {
    KotlinBuildTargetJO(
      languageVersion = kbt.languageVersion,
      apiVersion = kbt.apiVersion,
      kotlincOptions = kbt.kotlincOptions.asJava,
      associates = kbt.associates.map(new BuildTargetIdentifier(_)).asJava,
      jvmBuildTarget = kbt.jvmBuildTarget.map(jvmBuildTarget).orNull
    )
  }
}
