package mill.androidlib.bsp

import java.nio.file.Path

import mill.api.daemon.internal.bsp.BspJavaModuleApi
import mill.Task
import mill.api.daemon.internal.{EvaluatorApi, internal}
import mill.api.ModuleCtx
import mill.androidlib.AndroidModule
import mill.javalib.{JavaModule, SemanticDbJavaModule}
import mill.javalib.bsp.{BspJavaModule, BspModule}
import mill.api.JsonFormatters.given

trait BspAndroidModule extends BspJavaModule {

  def javaModuleRef: mill.api.ModuleRef[AndroidModule & BspModule]

  // TODO: edit this to pickup sources jar from task?
  override private[mill] def bspBuildTargetDependencyModules
  : Task.Simple[(
    mvnDeps: Seq[(String, String, String)],
    unmanagedClasspath: Seq[Path]
  )] = Task {
    (
      // full list of dependencies, including transitive ones
      jm.millResolver()
        .resolution(
          Seq(
            jm.coursierDependencyTask().withConfiguration(coursier.core.Configuration.provided),
            jm.coursierDependencyTask()
          )
        )
        .orderedDependencies
        .map { d => (d.module.organization.value, d.module.repr, d.version) },
      jm.unmanagedClasspath().map(_.path.toNIO)
    )
  }

}

object BspAndroidModule {
  trait Wrap(jm0: AndroidModule & BspModule) extends mill.api.Module {
    override def moduleCtx: ModuleCtx = jm0.moduleCtx
    override protected[mill] implicit def moduleNestedCtx: ModuleCtx.Nested = jm0.moduleNestedCtx
    @internal
    object internalBspJavaModule extends BspAndroidModule {
      private[mill] def isScript = jm0.isScript
      def javaModuleRef = mill.api.ModuleRef(jm0)
    }
  }

}
