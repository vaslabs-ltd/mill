package mill.javalib.quarkus

trait ApplicationModelWorker extends AutoCloseable {
  def quarkusBootstrapApplication(applicationModelFile: os.Path, destRunJar: os.Path, jar: os.Path, libDir: os.Path): os.Path
  def quarkusGenerateApplicationModel(
      appModel: ApplicationModelWorker.AppModel,
      destination: os.Path
  ): os.Path
}

object ApplicationModelWorker {
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
      dependencies: Seq[Dependency]
  )

  case class Dependency(groupId: String, artifactId: String, version: String, resolvedPath: os.Path)
}
