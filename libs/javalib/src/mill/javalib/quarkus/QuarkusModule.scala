package mill.javalib.quarkus

import coursier.core.VariantSelector.ConfigurationBased
import mill.api.PathRef
import mill.{T, Task}
import mill.javalib.{Dep, DepSyntax, JavaModule, PublishModule}
import mill.util.Jvm

import java.net.URLClassLoader

@mill.api.experimental
trait QuarkusModule extends JavaModule {

  def quarkusVersion: T[String]

  override def bomMvnDeps: Task.Simple[Seq[Dep]] = super.bomMvnDeps() ++ Seq(
    mvn"io.quarkus.platform:quarkus-bom:${quarkusVersion()}"
  )

  def quarkusBootstrapDeps: T[Seq[Dep]] = Task {
    Seq(
      mvn"io.quarkus:quarkus-bootstrap-core",
      mvn"io.quarkus:quarkus-bootstrap-app-model",
      mvn"io.quarkus:quarkus-bootstrap-maven-resolver",
      mvn"io.quarkus:quarkus-core-deployment"
    )
  }

  def quarkusApplicationModelWorkerResolvedDeps: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      quarkusBootstrapDeps() ++ Seq(Dep.millProjectModule("mill-libs-javalib-quarkus")),
      boms = allBomDeps()
    )
  }

  def quarkusApplicationModelWorkerClassloader: Task.Worker[URLClassLoader] = Task.Worker {

    val classpath = defaultResolver().classpath(
      quarkusBootstrapDeps() ++ Seq(Dep.millProjectModule("mill-libs-javalib-quarkus")),
      boms = allBomDeps()
    )

    Jvm.createClassLoader(classpath.map(_.path), parent = getClass.getClassLoader)
  }

  def quarkusApplicationModelWorker: Task.Worker[ApplicationModelWorker] = Task.Worker {
    quarkusApplicationModelWorkerClassloader().loadClass(
      "mill.javalib.quarkus.ApplicationModelWorkerImpl"
    )
      .getDeclaredConstructor()
      .newInstance()
      .asInstanceOf[ApplicationModelWorker]
  }

  def quarkusDependencies: Task[Seq[ApplicationModelWorker.Dependency]] = Task.Anon {
    val depRuntime = coursierDependencyTask().withVariantSelector(
      ConfigurationBased(coursier.core.Configuration.runtime)
    )

    val depCompile = coursierDependencyTask().withVariantSelector(
      ConfigurationBased(coursier.core.Configuration.compile)
    )

    val runtimeDeps =
      millResolver().artifacts(Seq(mill.javalib.BoundDep(depRuntime, force = false)))

    def qualifier(d: coursier.core.Dependency) =
      s"${d.module.organization.value}:${d.module.name.value}"

    def wQualifier(d: ApplicationModelWorker.Dependency) =
      s"${d.groupId}:${d.artifactId}"

    def isDirectDep(d: coursier.core.Module): Boolean =
      mvnDeps().exists(dep => dep.dep.module == d)

    val compileDeps =
      millResolver().artifacts(Seq(mill.javalib.BoundDep(depCompile, force = false)))

    val runtimeDepSet = runtimeDeps.detailedArtifacts0.map(da => qualifier(da._1)).toSet

    val quarkusPrecomputedRuntimeDeps = runtimeDeps.detailedArtifacts0.map {
      case (dependency, _, _, file) =>
        ApplicationModelWorker.Dependency(
          groupId = dependency.module.organization.value,
          artifactId = dependency.module.name.value,
          version = dependency.versionConstraint.asString,
          resolvedPath = os.Path(file),
          isRuntime = true,
          isDeployment = false,
          isTopLevelArtifact = isDirectDep(dependency.module),
          hasExtension = false
        )
    }

    val depsWithExtensions = quarkusApplicationModelWorker().quarkusDeploymentDependencies(
      quarkusPrecomputedRuntimeDeps
    )

    val extensionDepsSet = depsWithExtensions.map(wQualifier).toSet

    val deploymentMvnDeps = depsWithExtensions.map(d =>
      mvn"${d.groupId}:${d.artifactId}-deployment:${d.version}"
    )

    val deploymentDeps = millResolver().artifacts(
      deploymentMvnDeps
    )

    val deploymentDepsSet = deploymentDeps.detailedArtifacts0.map(da => qualifier(da._1)).toSet

    val quarkusDeploymentDeps = deploymentDeps.detailedArtifacts0.map {
      case (dependency, _, _, file) =>
        ApplicationModelWorker.Dependency(
          groupId = dependency.module.organization.value,
          artifactId = dependency.module.name.value,
          version = dependency.versionConstraint.asString,
          resolvedPath = os.Path(file),
          isRuntime = runtimeDepSet.contains(qualifier(dependency)),
          isDeployment = true,
          isTopLevelArtifact = isDirectDep(dependency.module),
          hasExtension = extensionDepsSet.contains(qualifier(dependency))
        )
    }

    val quarkusRuntimeDeps = quarkusPrecomputedRuntimeDeps.filterNot(d =>
      deploymentDepsSet.contains(wQualifier(d))
    )

    val quarkusCompileDeps =
      compileDeps.detailedArtifacts0.filterNot {
        da =>
          val q = qualifier(da._1)
          runtimeDepSet.contains(q) || deploymentDepsSet.contains(q)
      }.map {
        case (dependency, _, _, file) =>
          ApplicationModelWorker.Dependency(
            groupId = dependency.module.organization.value,
            artifactId = dependency.module.name.value,
            version = dependency.versionConstraint.asString,
            resolvedPath = os.Path(file),
            isRuntime = false,
            isDeployment = false,
            isTopLevelArtifact = isDirectDep(dependency.module),
            hasExtension = false
          )
      }

    quarkusRuntimeDeps ++ quarkusCompileDeps ++ quarkusDeploymentDeps
  }

  // TODO most reliable way to get this?
  def quarkusMillBuildFile: Task.Simple[PathRef] = Task.Input(PathRef(moduleDir / "build.mill"))

  def quarkusSerializedAppModel: T[PathRef] = this match {
    case m: PublishModule => Task {
        val modelPath = quarkusApplicationModelWorker().quarkusGenerateApplicationModel(
          ApplicationModelWorker.AppModel(
            projectRoot = moduleDir,
            buildDir = compile().classes.path,
            buildFile = quarkusMillBuildFile().path,
            quarkusVersion = quarkusVersion(),
            groupId = m.pomSettings().organization,
            artifactId = m.artifactId(),
            version = m.publishVersion(),
            sourcesDir = m.sources().head.path, // TODO support multiple
            resourcesDir = m.resources().head.path,
            compiledPath = m.compile().classes.path,
            compiledResources = m.compileResources().head.path, // TODO this is wrong, adjust later,
            boms = bomMvnDeps().map(_.formatted),
            dependencies = quarkusDependencies()
          ),
          Task.dest
        )
        PathRef(modelPath)
      }
    case _ => Task {
        val modelPath = quarkusApplicationModelWorker().quarkusGenerateApplicationModel(
          ApplicationModelWorker.AppModel(
            projectRoot = moduleDir,
            buildDir = compile().classes.path,
            buildFile = quarkusMillBuildFile().path,
            quarkusVersion = quarkusVersion(),
            groupId = "unspecified", // todo add organisation in quarkus module
            artifactId = artifactId(),
            version = "unspecified",
            sourcesDir = sources().head.path, // TODO support multiple
            resourcesDir = resources().head.path,
            compiledPath = compile().classes.path,
            compiledResources = compileResources().head.path, // TODO this is wrong, adjust later,
            boms = bomMvnDeps().map(_.formatted),
            dependencies = quarkusDependencies()
          ),
          Task.dest
        )
        PathRef(modelPath)
      }
  }

  def quarkusLibDir: T[PathRef] = Task {
    val dest = Task.dest
    resolvedMvnDeps().foreach(pr => os.copy.into(pr.path, dest))
    PathRef(dest)
  }

  def quarkusJar: T[PathRef] = Task {
    val dest = Task.dest / "quarkus"
    os.makeDir.all(dest)
    val jarPath = quarkusApplicationModelWorker().quarkusBootstrapApplication(
      quarkusSerializedAppModel().path,
      dest / "quarkus-run.jar", // TODO use quarkus utility function
      jar().path,
      quarkusLibDir().path
    )

    PathRef(jarPath)
  }

}
