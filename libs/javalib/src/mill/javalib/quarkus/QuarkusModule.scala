package mill.javalib.quarkus

import coursier.core.VariantSelector.ConfigurationBased
import mill.api.PathRef
import mill.{T, Task}
import mill.javalib.{Dep, DepSyntax, JavaModule, PublishModule}
import mill.util.Jvm
import upickle.default.ReadWriter.join

import java.net.URLClassLoader

@mill.api.experimental
trait QuarkusModule extends JavaModule {

  /**
   * The version of the quarkus platform. Used for
   * setting the `io.quarkus.platform:quarkus-bom` version
   * It's used for creating the quarkus Application Model and bootstrapping this Quarkus module
   */
  def quarkusVersion: T[String]

  /**
   * If this is a [[PublishModule]] then group id is derived from [[PublishModule.pomSettings]]
   * otherwise it's set to `unspecified`.
   *
   * It's used for creating the quarkus Application Model and bootstrapping this Quarkus module
   */
  def quarkusGroupId: T[String] = this match {
    case m: PublishModule =>
      Task {
        m.pomSettings().organization
      }
    case _ =>
      Task {
        "unspecified"
      }
  }

  /**
   * If this is a [[PublishModule]] then artifact version is derived from [[PublishModule.publishVersion]]
   * otherwise it's set to `0.0.1-SNAPSHOT`.
   *
   * It's used for creating the quarkus Application Model and bootstrapping this Quarkus module
   */
  def quarkusArtifactVersion: T[String] = this match {
    case m: PublishModule =>
      Task {
        m.publishVersion()
      }
    case _ =>
      Task {
        "0.0.1-SNAPSHOT"
      }
  }

  /**
   * The artifact id used to Quarkus bootstrap this module.
   * Defaults to [[artifactId]]
   */
  def quarkusArtifactId: T[String] = artifactId()

  override def bomMvnDeps: Task.Simple[Seq[Dep]] = super.bomMvnDeps() ++ Seq(
    mvn"io.quarkus.platform:quarkus-bom:${quarkusVersion()}"
  )

  /**
   * Dependencies for the Quarkus Bootstrap process
   * needed for [[quarkusApplicationModelWorker]]
   */
  def quarkusBootstrapDeps: T[Seq[Dep]] = Task {
    Seq(
      mvn"io.quarkus:quarkus-bootstrap-core",
      mvn"io.quarkus:quarkus-bootstrap-app-model",
      mvn"io.quarkus:quarkus-bootstrap-maven-resolver",
      mvn"io.quarkus:quarkus-core-deployment"
    )
  }

  /**
   * The native image for building this Quarkus module.
   * Can be mandrel, graalvm or a full image path.
   *
   * For more info see [[https://quarkus.io/guides/building-native-image#background]]
   */
  def quarkusNativeImage: T[String] = Task {
    "mandrel"
  }

  /**
   * Quarkus builds its own run classpath and manages it
   * via the launcher (quarkus-run.jar) which handles
   * running the application.
   */
  override def runClasspath: T[Seq[PathRef]] = Task {
    Seq(quarkusRunJar())
  }

  /**
   * Quarkus builds its own run classpath and manages it
   * via the launcher (quarkus-run.jar) which handles
   * and doesn't need a main class. However, for mill run to work
   * we need to put the Quarkus entrypoint here which is `io.quarkus.bootstrap.runner.QuarkusEntryPoint`
   */
  override def finalMainClass: T[String] = "io.quarkus.bootstrap.runner.QuarkusEntryPoint"

  def quarkusApplicationModelWorkerClassloader: Task.Worker[URLClassLoader] = Task.Worker {

    val classpath = defaultResolver().classpath(
      quarkusBootstrapDeps() ++ Seq(Dep.millProjectModule("mill-libs-javalib-quarkus-worker")),
      boms = allBomDeps()
    )

    Jvm.createClassLoader(classpath.map(_.path), parent = getClass.getClassLoader)
  }

  /**
   * The worker which provides the Quarkus Bootstrap process for creating
   * the quarkus Application Model and building the Application itself
   */
  def quarkusApplicationModelWorker: Task.Worker[ApplicationModelWorker] = Task.Worker {
    quarkusApplicationModelWorkerClassloader().loadClass(
      "mill.javalib.quarkus.ApplicationModelWorkerImpl"
    )
      .getDeclaredConstructor()
      .newInstance()
      .asInstanceOf[ApplicationModelWorker]
  }

  /**
   * These are the application dependencies that Quarkus needs to know about
   * for bootstrapping. They generally are in 3 categories:
   * 1. Runtime applications
   * 2. Deployment applications (that are also runtime)
   * 3. Compile applications
   * In addition, other helpful flags, help the [[ApplicationModelWorker]] to
   * flag these dependencies correctly, such as marking top level artifacts (i.e. direct dependencies).
   *
   * This mechanism is not fully implemneted yet, and only works for a single module.
   *
   * TODO send multiple modules to the Quarkus Bootstrap process using the moduleDeps
   */
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

  def quarkusSerializedAppModel: T[PathRef] = Task {
    val modelPath = quarkusApplicationModelWorker().quarkusGenerateApplicationModel(
      ApplicationModelWorker.AppModel(
        projectRoot = moduleDir,
        buildDir = compile().classes.path,
        buildFile = quarkusMillBuildFile().path,
        quarkusVersion = quarkusVersion(),
        groupId = quarkusGroupId(),
        artifactId = quarkusArtifactId(),
        version = quarkusArtifactVersion(),
        sourcesDir = sources().head.path, // TODO support multiple
        resourcesDir = resources().head.path,
        compiledPath = compile().classes.path,
        compiledResources = compileResources().head.path, // TODO this is wrong, adjust later,
        boms = bomMvnDeps().map(_.formatted),
        dependencies = quarkusDependencies(),
        nativeImage = quarkusNativeImage()
      ),
      Task.dest
    )
    PathRef(modelPath)

  }

  /**
   * The quarkus-run.jar which is a standalone fast jar
   * created by QuarkusBootstrap using the generated ApplicationModel
   * generated from the [[quarkusSerializedAppModel]]
   * @return the path of the quarkus-run.jar
   */
  def quarkusRunJar: T[PathRef] = Task {
    val dest = Task.dest / "quarkus"
    os.makeDir.all(dest)
    val jarPath = quarkusApplicationModelWorker().quarkusBootstrapApplication(
      quarkusSerializedAppModel().path,
      dest / "quarkus-run.jar", // TODO use quarkus utility function
      jar().path
    )

    PathRef(jarPath)
  }

}
