package mill.javalib.quarkus

import io.quarkus.bootstrap.BootstrapAppModelFactory
import io.quarkus.bootstrap.app.{ApplicationModelSerializer, AugmentAction, QuarkusBootstrap}
import io.quarkus.bootstrap.model.{ApplicationModelBuilder, PlatformImportsImpl, PlatformReleaseInfo}
import io.quarkus.bootstrap.util.BootstrapUtils
import io.quarkus.bootstrap.workspace.{ArtifactSources, SourceDir, WorkspaceModule, WorkspaceModuleId}
import io.quarkus.builder.{BuildChainBuilder, BuildContext}
import io.quarkus.deployment.builditem.AppModelProviderBuildItem
import io.quarkus.deployment.pkg.PackageConfig.JarConfig.JarType
import io.quarkus.deployment.pkg.builditem.JarBuildItem
import io.quarkus.maven.dependency.{ArtifactCoords, ResolvedDependencyBuilder}
import io.quarkus.paths.PathList
import io.quarkus.runner.bootstrap.AugmentActionImpl

import java.util.function.Consumer
import scala.jdk.CollectionConverters
import scala.jdk.CollectionConverters.*

class ApplicationModelWorkerImpl extends ApplicationModelWorker {

  def quarkusBootstrapApplication(applicationModelFile: os.Path, destRunJar: os.Path, jar: os.Path, libDir: os.Path): os.Path = {
    val applicationModel = ApplicationModelSerializer
      .deserialize(applicationModelFile.toNIO)

    val quarkusBootstrap = QuarkusBootstrap.builder()
      .setApplicationRoot(applicationModel.getApplicationModule.getModuleDir.toPath) // TODO this won't be always the case
      .setExistingModel(applicationModel)
      .build()

    val buildItem = new Consumer[BuildChainBuilder] {
      override def accept(t: BuildChainBuilder): Unit = {
        t.addBuildStep(
          buildStep = (context: BuildContext) => context.produce(
            new AppModelProviderBuildItem(applicationModel)
          )
        )
        t.addBuildStep(
          buildStep = (context: BuildContext) => context.produce(
            new JarBuildItem(
              destRunJar.toNIO,
              jar.toNIO,
              libDir.toNIO,
              JarType.FAST_JAR,
              "main" // TODO adjust
            )
          )
        )
      }
    }



    val augmentAction: AugmentAction = new AugmentActionImpl(quarkusBootstrap.bootstrap(), List(buildItem).asJava, List.empty.asJava)


    os.Path(augmentAction.createProductionApplication().getJar.getPath)
  }

  override def quarkusGenerateApplicationModel(
      appModel: ApplicationModelWorker.AppModel,
      destination: os.Path
  ): os.Path = {
    val factory = BootstrapAppModelFactory.newInstance()
    factory.setProjectRoot(appModel.projectRoot.toNIO)

    def toResolvedDependencyBuilder(dep: ApplicationModelWorker.Dependency)
        : ResolvedDependencyBuilder = {
      ResolvedDependencyBuilder.newInstance()
        .setResolvedPath(dep.resolvedPath.toNIO)
        .setGroupId(dep.groupId)
        .setArtifactId(dep.artifactId)
        .setVersion(dep.version)
        .setFlags(12) // TODO properly flag the dependencies
    }

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
        .setDependencies(appModel.dependencies.map(toResolvedDependencyBuilder).asJava)
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
    )}

    boms.foreach(platformImport.getImportedPlatformBoms.add)

    platformImport.getPlatformReleaseInfo.add(
      PlatformReleaseInfo("io.quarkus.platform", appModel.quarkusVersion, appModel.quarkusVersion, boms.toList.asJava)
    )

    val modelBuilder = new ApplicationModelBuilder()
      .setAppArtifact(resolvedDependencyBuilder)
      .setPlatformImports(platformImport)
      .addDependencies(appModel.dependencies.map(toResolvedDependencyBuilder).asJava)

    val targetFile = BootstrapUtils.resolveSerializedAppModelPath(destination.toNIO)
    ApplicationModelSerializer.serialize(
      modelBuilder.build(),
      targetFile
    )

    os.Path(targetFile)

  }

  override def close(): Unit = {
    // no op
  }
}
