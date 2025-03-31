package mill.kotlinlib.android

import mill.api.{PathRef, Result}
import mill.kotlinlib.DepSyntax
import mill.kotlinlib.android.AndroidHiltSupport.HiltGeneratedSources
import mill.kotlinlib.ksp.KspModule
import mill.kotlinlib.worker.api.KotlinWorkerTarget
import mill.{T, Task}
import os.Path

import java.io.File
import java.nio.file.{Files, StandardCopyOption}

@mill.api.experimental
trait AndroidHiltSupport extends KspModule with AndroidAppKotlinModule {

  override def sources: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }

  override def kspSources: T[Seq[PathRef]] = Task { super.sources() }

  override def generatedSources: T[Seq[PathRef]] = super[AndroidAppKotlinModule].generatedSources

  override def kspClasspath: T[Seq[PathRef]] =
   Seq(androidProcessResources()) ++ super.kspClasspath()

  def processorPath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      kotlinSymbolProcessors().flatMap {
        dep =>
          if (dep.dep.module.name.value == "hilt-android-compiler" && 
            dep.dep.module.organization.value == "com.google.dagger"
          )
            Seq(
              dep,
              ivy"com.google.dagger:hilt-compiler:${dep.version}"
            )
          else
            Seq(dep)
      }
    )
  }

  override def kspPluginsResolved: T[Seq[PathRef]] = Task {
    super.kspPluginsResolved()
  }

  override def kotlinSymbolProcessorsResolved: T[Seq[PathRef]] = Task {
    super.kotlinSymbolProcessorsResolved()
  }

//  private def jetify(ref: PathRef, jetifierStandalonePath: PathRef)(implicit ctx: mill.api.Ctx): PathRef = {
//    val baseName = ref.path.baseName
//    val jetifiedBaseName = s"jetified-${baseName}"
//    val destination = ref.path / os.up / s"${jetifiedBaseName}.${ref.path.ext}"
//    val cliArgs = Seq(
//      jetifierStandalonePath.path.toString,
//      "-i",
//      ref.path.toString,
//      "-o",
//      destination.toString
//    )
//    ctx.log.debug(s"Running ${cliArgs.mkString(" ")}")
//    val standaloneJetifierOut = os.call(
//      cliArgs,
//      check = false
//    )
//
//    if (standaloneJetifierOut.exitCode != 0) {
//      ctx.log.debug(s"Ignoring jetification of ${ref.path}")
//      ctx.log.debug(standaloneJetifierOut.out.text())
//      ctx.log.debug(standaloneJetifierOut.err.text())
//      ref
//    } else {
//      PathRef(destination)
//    }
//
//  }

  def jetifierStandalonePath: T[PathRef] = Task {
//    val zipFile = Task.dest / "jetifier-standalone.zip"
//    val zipFileExtracted = Task.dest / "extraxted"
//    os.makeDir.all(zipFileExtracted)
//    os.write(zipFile, requests.get(androidSdkModule().jetifierStandaloneUrl()).bytes)
//    val isWin = scala.util.Properties.isWin
//
//
//    os.unzip(zipFile, zipFileExtracted)
//
//    val executableName = if (isWin)
//      "jetifier-standalone.bat"
//    else
//      "jetifier-standalone"
//
//    val executablePath = zipFileExtracted / "jetifier-standalone" / "bin" / executableName
//
//    if (!isWin) os.perms.set(executablePath, "rwxrwxrwx")

    PathRef(os.home / "jetifier-standalone" / "bin" / "jetifier-standalone")
  }


  override def kspPluginParameters: T[Seq[String]] = Task {
    super.kspPluginParameters() ++
      Seq(
        s"apoption=dagger.fastInit=enabled",
        s"apoption=dagger.hilt.android.internal.disableAndroidSuperclassValidation=true",
        s"apoption=dagger.hilt.android.internal.projectType=APP",
        s"apoption=dagger.hilt.internal.useAggregatingRootProcessor=true"
      )
  }

  def androidJavaCompileKSPGeneratedSources: T[HiltGeneratedSources] = Task {
    val directory = Task.dest / "ap_generated/out"

    os.makeDir.all(directory)
    val generatedKSPSources = generateSourcesWithKSP()

    val generatedPath = Task.dest / "generated"
    os.makeDir(generatedPath)

    val javaSources = generatedPath / "java"

    os.copy(generatedKSPSources.java.path, javaSources)

    val daggerSourcesOrigin = generatedPath / "java/dagger"
    val hiltAggregatedDepsSourcesOrigin = generatedPath / "java/hilt_aggregated_deps"


    val hiltSources = generatedPath / "hilt"
    os.makeDir(hiltSources)

    val daggerSources = hiltSources / "dagger"
    val hiltAggregatedDepsSources = hiltSources / "hilt_aggregated_deps"

    os.move(daggerSourcesOrigin, daggerSources)
    os.move(hiltAggregatedDepsSourcesOrigin, hiltAggregatedDepsSources)

    val javaGeneratedSources = Seq(daggerSources, hiltAggregatedDepsSources, javaSources)
      .flatMap(os.walk(_))
      .filter(os.exists)
      .filter(_.ext == "java")

    val kotlinClasses = Task.dest / "kotlin"

    os.makeDir.all(kotlinClasses)

    val kotlinClasspath = compileClasspath() :+ androidProcessResources()


    val kotlinSourceFiles: Seq[Path] = kspSources().map(_.path).flatMap(os.walk(_))
      .filter(path => Seq("kt", "kts").contains(path.ext.toLowerCase()))

    val compileWithKotlin = Seq(
      "-d", kotlinClasses.toString,
      "-classpath", kotlinClasspath.map(_.path).mkString(File.pathSeparator)
    ) ++ kotlincOptions() ++ kotlinSourceFiles.map(_.toString) ++
      javaGeneratedSources.map(_.toString) ++
      Seq(daggerSources.toString, hiltAggregatedDepsSources.toString)

    Task.log.info(s"Compiling kotlin classes ${compileWithKotlin.mkString(" ")}")

    kotlinWorkerTask().compile(KotlinWorkerTarget.Jvm, compileWithKotlin)

    val kspJavacOptions = Seq(
      "-XDstringConcat=inline",
      "-Adagger.fastInit=enabled",
      "-Adagger.hilt.internal.useAggregatingRootProcessor=true",
      "-Adagger.hilt.android.internal.disableAndroidSuperclassValidation=true",
      "-g",
      "-XDuseUnsharedTable=true",
      "-proc:none",
      "-parameters",
      "-s", directory.toString,
    )

    val compileCp = compileClasspath().map(_.path) ++ Seq(androidProcessResources().path, kotlinClasses)

    val worker = zincWorkerRef().worker()

    Task.log.info(
      s"Compiling ${javaGeneratedSources.size} Java generated sources to ${directory} ..."
    )

    val compilation = worker.compileJava(
      upstreamCompileOutput = Seq.empty,
      sources = javaGeneratedSources,
      compileClasspath = compileCp,
      javacOptions = kspJavacOptions,
      reporter = T.ctx().reporter(hashCode()),
      reportCachedProblems = zincReportCachedProblems(),
      incrementalCompilation = true
    ).get

    val hiltAggregatedDepsCompiledOrigin = compilation.classes.path / "hilt_aggregated_deps"
    val daggerCompiledOrigin = compilation.classes.path / "dagger"
    val hiltDir = Task.dest / "hilt"
    os.makeDir(hiltDir)

    val hiltAggregatedDepsCompiled = hiltDir / "hilt_aggregated_deps"
    val daggerCompiled = hiltDir / "dagger"

    os.move(from = hiltAggregatedDepsCompiledOrigin, hiltAggregatedDepsCompiled)
    os.move(from = daggerCompiledOrigin, daggerCompiled)

    HiltGeneratedSources(
      apGenerated = PathRef(directory),
      javaSources = PathRef(javaSources),
      javaCompiled = compilation.classes,
      kotlinCompiled = PathRef(kotlinClasses),
      hiltAggregatedDepsSources = PathRef(hiltAggregatedDepsSources),
      hiltAggregatedDepsCompiled = PathRef(hiltAggregatedDepsCompiled),
      daggerSources = PathRef(daggerSources),
      daggerCompiled = PathRef(daggerCompiled)
    )
  }

  def androidHiltGeneratedSources: T[Seq[PathRef]] = Task {
    val directory = Task.dest / "component_classes" / "out"
    os.makeDir.all(directory)

    val compiledKspSources: HiltGeneratedSources = androidJavaCompileKSPGeneratedSources()

    val hiltJavacOptions = Seq(
      "-processorpath", processorPath().map(_.path.toString).mkString(File.pathSeparator),
      "-XDstringConcat=inline",
      "-Adagger.fastInit=enabled",
      "-Adagger.hilt.internal.useAggregatingRootProcessor=false",
      "-Adagger.hilt.android.internal.disableAndroidSuperclassValidation=true",
      "-XDuseUnsharedTable=true",
      "-parameters",
      "-s", directory.toString,
    )

    val compileCp = hiltProcessorClasspath().map(_.path) ++
      Seq(
        compiledKspSources.kotlinCompiled.path,
        compiledKspSources.javaCompiled.path,
        compiledKspSources.daggerCompiled.path / os.up,
        compiledKspSources.javaSources.path
      )

    val worker = zincWorkerRef().worker()

    val classes = Task.dest / "classes"
    os.makeDir.all(classes)


    val processedRoots = os.walk(
      compiledKspSources.daggerSources.path / "hilt/internal/processedrootsentinel"
    ).filter(_.ext == "java")

    val componentTreeDeps = os.walk(compiledKspSources.javaSources.path)
      .filter(_.toString.endsWith("_ComponentTreeDeps.java"))

    val sourcesToCompile = processedRoots ++ componentTreeDeps


    T.log.info(s"Compiling ${sourcesToCompile.mkString(" ")} java sources to ${classes}")

    val result = worker.compileJava(
      upstreamCompileOutput = upstreamCompileOutput(),
      sources = sourcesToCompile,
      compileClasspath = compileCp,
      javacOptions = hiltJavacOptions,
      reporter = T.ctx().reporter(hashCode()),
      reportCachedProblems = zincReportCachedProblems(),
      incrementalCompilation = false
    )

    val mergedClasses = Task.dest / "merged_classes"
    os.makeDir(mergedClasses)

    def merge(path: os.Path, mergedClasses: os.Path = mergedClasses): Unit =
      os.copy(path, mergedClasses, mergeFolders = true, replaceExisting = true)

    // result classes have precedence over the compiledKspSources classes
    merge(compiledKspSources.javaCompiled.path)
    merge(compiledKspSources.kotlinCompiled.path)
    merge(compiledKspSources.hiltAggregatedDepsCompiled.path, mergedClasses / "hilt_aggregated_deps")
    merge(compiledKspSources.daggerSources.path, mergedClasses / "dagger")
    merge(result.get.classes.path)

    Seq(PathRef(mergedClasses))

  }

  override def androidGeneratedCompiledClasses: T[Seq[PathRef]] =
    androidHiltGeneratedSources

  def hiltProcessorClasspath: T[Seq[PathRef]] = Task {
    kspApClasspath() ++ compileClasspath()
  }

  override def kotlinPrecompiledClasses: Task[Seq[PathRef]] = androidGeneratedCompiledClasses
}

object AndroidHiltSupport {
  case class HiltGeneratedSources(
                                   apGenerated: PathRef,
                                   javaSources: PathRef,
                                   kotlinCompiled: PathRef,
                                   javaCompiled: PathRef,
                                   daggerSources: PathRef,
                                   daggerCompiled: PathRef,
                                   hiltAggregatedDepsSources: PathRef,
                                   hiltAggregatedDepsCompiled: PathRef
  ) {
    def classpath = Seq(kotlinCompiled, javaCompiled)
  }

  object HiltGeneratedSources {
    implicit def resultRW: upickle.default.ReadWriter[HiltGeneratedSources] = upickle.default.macroRW
  }
}
