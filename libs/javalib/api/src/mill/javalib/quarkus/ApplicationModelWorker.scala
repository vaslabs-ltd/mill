package mill.javalib.quarkus

import mill.api.daemon.experimental

@experimental
trait ApplicationModelWorker extends AutoCloseable {
  def quarkusBootstrapApplication(
      applicationModelFile: os.Path,
      destRunJar: os.Path,
      jar: os.Path
  ): os.Path
  def quarkusGenerateApplicationModel(
      appModel: ApplicationModelWorker.AppModel,
      destination: os.Path
  ): os.Path

  def quarkusDeploymentDependencies(runtimeDeps: Seq[ApplicationModelWorker.Dependency])
      : Seq[ApplicationModelWorker.Dependency]
}

object ApplicationModelWorker {

  /**
   * This app model has the necessary
   * elements to build the Quarkus Application Model.
   *
   * The effort for Quarkus support is ongoing.
   *
   * For details on the requirements see [[https://github.com/quarkusio/quarkus/tree/main/independent-projects/bootstrap/app-model/src/main/java/io/quarkus/bootstrap/model]]
   */
  case class AppModel(
      projectRoot: os.Path,
      buildDir: os.Path,
      buildFile: os.Path,
      quarkusVersion: String,
      groupId: String,
      artifactId: String,
      version: String,
      sourcesDir: os.Path,
      resourcesDir: os.Path,
      compiledPath: os.Path,
      compiledResources: os.Path,
      boms: Seq[String],
      dependencies: Seq[Dependency],
      nativeImage: String
  )

  case class Dependency(
      groupId: String,
      artifactId: String,
      version: String,
      resolvedPath: os.Path,
      isRuntime: Boolean,
      isDeployment: Boolean,
      isTopLevelArtifact: Boolean,
      hasExtension: Boolean
  )
}
