package mill.javalib.quarkus

import io.quarkus.bootstrap.app.{ApplicationModelSerializer, AugmentAction, QuarkusBootstrap}
import io.quarkus.bootstrap.model.{
  ApplicationModelBuilder,
  CapabilityContract,
  PlatformImportsImpl,
  PlatformReleaseInfo
}
import io.quarkus.bootstrap.util.BootstrapUtils
import io.quarkus.bootstrap.workspace.{
  ArtifactSources,
  SourceDir,
  WorkspaceModule,
  WorkspaceModuleId
}
import io.quarkus.bootstrap.{BootstrapAppModelFactory, BootstrapConstants}
import io.quarkus.fs.util.ZipUtils
import io.quarkus.maven.dependency.{ArtifactCoords, DependencyFlags, ResolvedDependencyBuilder}
import io.quarkus.paths.PathList
import io.quarkus.runner.bootstrap.AugmentActionImpl

import java.nio.file.Files
import java.util.Properties
import scala.jdk.CollectionConverters
import scala.jdk.CollectionConverters.*
import scala.util.Using

class ApplicationModelWorkerImpl extends ApplicationModelWorker {

  def quarkusBootstrapApplication(
      applicationModelFile: os.Path,
      destRunJar: os.Path,
      jar: os.Path,
      libDir: os.Path
  ): os.Path = {
    val applicationModel = ApplicationModelSerializer
      .deserialize(applicationModelFile.toNIO)

    val quarkusBootstrap = QuarkusBootstrap.builder()
      .setApplicationRoot(
        applicationModel.getApplicationModule.getModuleDir.toPath
      ) // TODO this won't be always the case
      .setExistingModel(applicationModel)
      .setLocalProjectDiscovery(true)
      .build()

    val augmentAction: AugmentAction =
      new AugmentActionImpl(quarkusBootstrap.bootstrap(), List.empty.asJava, List.empty.asJava)

    os.Path(augmentAction.createProductionApplication().getJar.getPath)
  }

  override def quarkusDeploymentDependencies(runtimeDeps: Seq[ApplicationModelWorker.Dependency])
      : Seq[ApplicationModelWorker.Dependency] = {
    runtimeDeps.filter(
      hasDeploymentDep
    )
  }

  override def quarkusGenerateApplicationModel(
      appModel: ApplicationModelWorker.AppModel,
      destination: os.Path
  ): os.Path = {
    val factory = BootstrapAppModelFactory.newInstance()
    factory.setProjectRoot(appModel.projectRoot.toNIO)

    def toResolvedDependencyBuilder(dep: ApplicationModelWorker.Dependency)
        : ResolvedDependencyBuilder = {
      val builder = ResolvedDependencyBuilder.newInstance()
        .setResolvedPath(dep.resolvedPath.toNIO)
        .setGroupId(dep.groupId)
        .setArtifactId(dep.artifactId)
        .setVersion(dep.version)

      if (dep.isRuntime) {
        builder.setRuntimeCp()
      }

      builder.setDirect(dep.isTopLevelArtifact)

      if (dep.hasExtension) {
        builder.setFlags(DependencyFlags.RUNTIME_EXTENSION_ARTIFACT)
        builder.setDeploymentCp()
        if (dep.isTopLevelArtifact)
          builder.setFlags(DependencyFlags.TOP_LEVEL_RUNTIME_EXTENSION_ARTIFACT)
      }

      if (dep.isDeployment)
        builder.setDeploymentCp()

      builder
    }

    val dependencies = appModel.dependencies.map(toResolvedDependencyBuilder)

    val resolvedDependencyBuilder = ResolvedDependencyBuilder.newInstance().setWorkspaceModule(
      WorkspaceModule.builder()
        .setModuleDir(appModel.projectRoot.toNIO)
        .setModuleId(
          WorkspaceModuleId.of(appModel.groupId, appModel.artifactId, appModel.version)
        ).addArtifactSources(
          ArtifactSources.main(
            // TODO generated sources?
            SourceDir.of(appModel.sourcesDir.toNIO, appModel.compiledPath.toNIO),
            SourceDir.of(appModel.resourcesDir.toNIO, appModel.compiledResources.toNIO)
          )
        ).setBuildDir(appModel.buildDir.toNIO)
        .setDependencies(dependencies.asJava)
        .setBuildFile(appModel.buildFile.toNIO)
        .build()
    ).setResolvedPaths(PathList.of(appModel.compiledPath.toNIO, appModel.compiledResources.toNIO))
      .setGroupId(appModel.groupId)
      .setArtifactId(appModel.artifactId)
      .setVersion(appModel.version)

    val platformImport = new PlatformImportsImpl()

    val boms: Seq[ArtifactCoords] = appModel.boms.map { bom =>
      val parts = bom.split(":") // todo make a bom model in the AppModel

      ArtifactCoords.pom(
        parts(0),
        parts(1),
        parts(2)
      )
    }

    boms.foreach(platformImport.getImportedPlatformBoms.add)

    platformImport.getPlatformReleaseInfo.add(
      PlatformReleaseInfo(
        "io.quarkus.platform",
        appModel.quarkusVersion,
        appModel.quarkusVersion,
        boms.toList.asJava
      )
    )

    val modelBuilder = new ApplicationModelBuilder()
      .setAppArtifact(resolvedDependencyBuilder)
      .setPlatformImports(platformImport)
      .addDependencies(dependencies.asJava)

    processQuarkusDependency(resolvedDependencyBuilder, modelBuilder)

    dependencies.foreach((resolvedDependencyBuilder: ResolvedDependencyBuilder) =>
      processQuarkusDependency(resolvedDependencyBuilder, modelBuilder)
    )

    val targetFile = BootstrapUtils.resolveSerializedAppModelPath(destination.toNIO)
    ApplicationModelSerializer.serialize(
      modelBuilder.build(),
      targetFile
    )

    os.Path(targetFile)

  }

  // utility function adapted from io.quarkus.gradle.tooling.GradleApplicationModelBuilder
  private def processQuarkusDependency(
      artifactBuilder: ResolvedDependencyBuilder,
      modelBuilder: ApplicationModelBuilder
  ): Unit = {
    artifactBuilder.getResolvedPaths.asScala.filter(p =>
      Files.exists(p) && artifactBuilder.getType == ArtifactCoords.TYPE_JAR
    ).foreach {
      artifactPath =>
        if (Files.isDirectory(artifactPath)) {
          processQuarkusDir(
            artifactBuilder = artifactBuilder,
            quarkusDir = artifactPath.resolve(BootstrapConstants.META_INF),
            modelBuilder = modelBuilder
          )
        } else {
          Using.resource(ZipUtils.newFileSystem(artifactPath))(artifactFs =>
            processQuarkusDir(
              artifactBuilder = artifactBuilder,
              quarkusDir = artifactFs.getPath(BootstrapConstants.META_INF),
              modelBuilder = modelBuilder
            )
          )
        }
    }

  }

  private def hasDeploymentDep(dep: ApplicationModelWorker.Dependency): Boolean = {
    val artifact = dep.resolvedPath
    val metaInfPathExists = Using.resource(ZipUtils.newFileSystem(artifact.toNIO)) { artifactFs =>
      val quarkusDir = artifactFs.getPath(BootstrapConstants.META_INF)
      val quarkusDescr = quarkusDir.resolve(BootstrapConstants.DESCRIPTOR_FILE_NAME)
      Files.exists(quarkusDescr)
    }
    metaInfPathExists
  }

  private def processQuarkusDir(
      artifactBuilder: ResolvedDependencyBuilder,
      quarkusDir: java.nio.file.Path,
      modelBuilder: ApplicationModelBuilder
  ): Unit = {
    val quarkusDescr = quarkusDir.resolve(BootstrapConstants.DESCRIPTOR_FILE_NAME)

    if (Files.exists(quarkusDescr)) {
      val extProps = readDescriptior(quarkusDescr)

      artifactBuilder.setRuntimeExtensionArtifact()
      modelBuilder.handleExtensionProperties(extProps, artifactBuilder.getKey)

      val providesCapabilities =
        Option(extProps.getProperty(BootstrapConstants.PROP_PROVIDES_CAPABILITIES))

      providesCapabilities.foreach(cap =>
        modelBuilder
          .addExtensionCapabilities(CapabilityContract.of(artifactBuilder.toGACTVString, cap, null))
      )

    }
  }

  private def readDescriptior(path: java.nio.file.Path): Properties = {
    val rtProps = new Properties()

    Using.resource(Files.newBufferedReader(path))(br => rtProps.load(br))

    rtProps
  }

  override def close(): Unit = {
    // no op
  }
}
