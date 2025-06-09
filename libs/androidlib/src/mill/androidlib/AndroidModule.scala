package mill.androidlib

import coursier.{Repository, core as cs}
import coursier.core.VariantSelector.VariantMatcher
import coursier.params.ResolutionParams
import mill.T
import mill.androidlib.manifestmerger.AndroidManifestMerger
import mill.define.{ModuleRef, PathRef, Task}
import mill.scalalib.*
import mill.define.JsonFormatters.given
import mill.scalalib.api.CompilationResult

import scala.collection.immutable
import scala.xml.*

trait AndroidModule extends JavaModule {

  // https://cs.android.com/android-studio/platform/tools/base/+/mirror-goog-studio-main:build-system/gradle-core/src/main/java/com/android/build/gradle/internal/tasks/D8BundleMainDexListTask.kt;l=210-223;drc=66ab6bccb85ce3ed7b371535929a69f494d807f0
  val mainDexPlatformRules = Seq(
    "-keep public class * extends android.app.Instrumentation {\n" +
      "  <init>(); \n" +
      "  void onCreate(...);\n" +
      "  android.app.Application newApplication(...);\n" +
      "  void callApplicationOnCreate(android.app.Application);\n" +
      "}",
    "-keep public class * extends android.app.Application { " +
      "  <init>();\n" +
      "  void attachBaseContext(android.content.Context);\n" +
      "}",
    "-keep public class * extends android.app.backup.BackupAgent { <init>(); }",
    "-keep public class * extends android.test.InstrumentationTestCase { <init>(); }"
  )

  /**
   * Adds "aar" to the handled artifact types.
   */
  override def artifactTypes: T[Set[coursier.Type]] =
    Task {
      super.artifactTypes() + coursier.Type("aar")
    }

  override def sources: T[Seq[PathRef]] = Task.Sources("src/main/java")

  /**
   * Provides access to the Android SDK configuration.
   */
  def androidSdkModule: ModuleRef[AndroidSdkModule]

  def androidManifestLocation: T[PathRef] = Task.Source("src/main/AndroidManifest.xml")

  /**
   * Provides os.Path to an XML file containing configuration and metadata about your android application.
   * TODO dynamically add android:debuggable
   */
  def androidManifest: T[PathRef] = Task {
    val manifestFromSourcePath = androidManifestLocation().path

    val manifestElem = XML.loadFile(manifestFromSourcePath.toString()) %
      Attribute(None, "xmlns:android", Text("http://schemas.android.com/apk/res/android"), Null)
    // add the application package
    val manifestWithPackage =
      manifestElem % Attribute(None, "package", Text(androidNamespace), Null)

    val generatedManifestPath = Task.dest / "AndroidManifest.xml"
    os.write(generatedManifestPath, manifestWithPackage.mkString)

    PathRef(generatedManifestPath)
  }

  /**
   * Controls debug vs release build type. Default is `true`, meaning debug build will be generated.
   *
   * This option will probably go away in the future once build variants are supported.
   */
  def androidIsDebug: T[Boolean] = {
    true
  }

  /**
   * The minimum SDK version to use. Default is 1.
   *
   * See [[https://developer.android.com/guide/topics/manifest/uses-sdk-element.html#min]] for more details.
   */
  def androidMinSdk: T[Int] = 1

  /**
   * This setting defines which Android API level your project compiles against. This is a required property for
   * Android builds.
   *
   * It determines what Android APIs are available during compilation - your code can only use APIs from this level
   * or lower.
   *
   * See [[https://developer.android.com/guide/topics/manifest/uses-sdk-element.html#ApiLevels]] for more details.
   */
  def androidCompileSdk: T[Int]

  /**
   * The target SDK version to use. Default is equal to the [[androidCompileSdk]] value.
   *
   * See [[https://developer.android.com/guide/topics/manifest/uses-sdk-element.html#target]] for more details.
   */
  def androidTargetSdk: T[Int] = androidCompileSdk

  /**
   * The version name of the application. Default is "1.0".
   *
   * See [[https://developer.android.com/studio/publish/versioning]] for more details.
   */
  def androidVersionName: T[String] = "1.0"

  /**
   * Version code of the application. Default is 1.
   *
   * See [[https://developer.android.com/studio/publish/versioning]] for more details.
   */
  def androidVersionCode: T[Int] = 1

  /**
   * Specifies AAPT options for Android resource compilation.
   */
  def androidAaptOptions: T[Seq[String]] = Task {
    Seq("--auto-add-overlay")
  }

  /**
   * Gets all the android resources (typically in res/ directory)
   * from the library dependencies using [[androidUnpackedArchives]]
   *
   * @return
   */
  def androidLibraryResources: T[Seq[PathRef]] = Task {
    androidUnpackedArchives().flatMap(_.androidResources.toSeq)
  }

  override def repositoriesTask: Task[Seq[Repository]] = Task.Anon {
    super.repositoriesTask() :+ AndroidSdkModule.mavenGoogle
  }

  override def checkGradleModules: T[Boolean] = true
  override def resolutionParams: Task[ResolutionParams] = Task.Anon {
    super.resolutionParams().addVariantAttributes(
      "org.jetbrains.kotlin.platform.type" ->
        VariantMatcher.AnyOf(Seq(
          VariantMatcher.Equals("androidJvm"),
          VariantMatcher.Equals("common"),
          VariantMatcher.Equals("jvm")
        )),
      "org.gradle.category" -> VariantMatcher.Library,
      "org.gradle.jvm.environment" ->
        VariantMatcher.AnyOf(Seq(
          VariantMatcher.Equals("android"),
          VariantMatcher.Equals("common"),
          VariantMatcher.Equals("standard-jvm")
        ))
    )
  }

  /**
   * The original compiled classpath (containing a mix of jars and aars).
   * @return
   */
  def androidOriginalCompileClasspath: T[Seq[PathRef]] = Task {
    super.compileClasspath()
  }

  /**
   * The original run classpath (containing a mix of jars and aars).
   *
   * @return
   */
  def androidOriginalRunClasspath: T[Seq[PathRef]] = Task {
    super.runClasspath()
  }

  /**
   * The generated android sources from [[androidResources]].
   */
  def androidGeneratedRSources: T[Seq[PathRef]] = Task {
    os.walk(androidCompiledResourcesApk().generatedSources.path).filter(_.ext == "java")
      .map(PathRef(_))
  }

  /**
   * The Java compiled classes of [[androidResources]]
   */
  def androidRClasses: T[CompilationResult] = Task(persistent = true) {
    jvmWorker()
      .worker()
      .compileJava(
        upstreamCompileOutput = upstreamCompileOutput(),
        sources =
          os.walk(androidCompiledResourcesApk().generatedSources.path).filter(_.ext == "java"),
        compileClasspath = Seq.empty,
        javacOptions = javacOptions() ++ mandatoryJavacOptions(),
        reporter = Task.reporter.apply(hashCode),
        reportCachedProblems = zincReportCachedProblems(),
        incrementalCompilation = zincIncrementalCompilation()
      )
  }

  /** The classpath containing only the R Classes */
  def androidRTransitiveClasspath: T[Seq[PathRef]] = Task {
    Task.traverse(transitiveModuleCompileModuleDeps) {
      case m: AndroidModule =>
        Task.Anon(Seq(m.androidRClasses().classes))
      case _ =>
        Task.Anon(Seq.empty[PathRef])
    }().flatten
  }

  /**
   * Replaces AAR files in [[androidOriginalCompileClasspath]] with their extracted JARs.
   */
  override def compileClasspath: T[Seq[PathRef]] = Task {
    // TODO process metadata shipped with Android libs. It can have some rules with Target SDK, for example.
    // TODO support baseline profiles shipped with Android libs.
    (androidOriginalCompileClasspath().filter(_.path.ext != "aar")
      ++ androidResolvedMvnDeps() ++ androidRTransitiveClasspath() ++ Seq(
        androidRClasses().classes
      )).map(
      _.path
    ).distinct.map(PathRef(_))
  }

  /**
   * Android res folder
   */
  def androidResources: T[Seq[PathRef]] = Task.Sources {
    moduleDir / "src/main/res"
  }

  /**
   * Resolves run mvn deps using [[resolvedRunMvnDeps]] and transforms
   * any aar files to jars
   * @return
   */
  def androidResolvedRunMvnDeps: T[Seq[PathRef]] = Task {
    transformedAndroidDeps(Task.Anon(resolvedRunMvnDeps()))()
  }

  /**
   * Resolves mvn deps using [[resolvedMvnDeps]] and transforms
   * any aar files to jars
   *
   * @return
   */
  def androidResolvedMvnDeps: T[Seq[PathRef]] = Task {
    transformedAndroidDeps(Task.Anon(resolvedMvnDeps()))()
  }

  protected def transformedAndroidDeps(resolvedDeps: Task[Seq[PathRef]]): Task[Seq[PathRef]] =
    Task.Anon {
      val transformedAarFilesToJar: Seq[PathRef] =
        androidTransformAarFiles(Task.Anon(resolvedDeps()))()
          .flatMap(_.classesJar)
      val jarFiles = resolvedDeps()
        .filter(_.path.ext == "jar")
        .distinct
      transformedAarFilesToJar ++ jarFiles
    }

  def androidTransformAarFiles(resolvedDeps: Task[Seq[PathRef]]): Task[Seq[UnpackedDep]] =
    Task.Anon {
      val transformDest = Task.dest / "transform"
      val aarFiles = resolvedDeps()
        .map(_.path)
        .filter(_.ext == "aar")
        .distinct

      extractAarFiles(aarFiles, transformDest)
    }

  /**
   * Extracts JAR files and resources from AAR dependencies.
   */
  def androidUnpackedArchives: T[Seq[UnpackedDep]] = Task {
    // The way Android is handling configurations for dependencies is a bit different from canonical Maven: it has
    // `api` and `implementation` configurations. If dependency is `api` dependency, it will be exposed to consumers
    // of the library, but if it is `implementation`, then it won't. The simplest analogy is api = compile,
    // implementation = runtime, but both are actually used for compilation and packaging of the final DEX.
    // More here https://docs.gradle.org/current/userguide/java_library_plugin.html#sec:java_library_separation.
    //
    // In Gradle terms using only `resolvedRunMvnDeps` won't be complete, because source modules can be also
    // api/implementation, but Mill has no such configurations.
    val aarFiles = androidOriginalCompileClasspath()
      .map(_.path)
      .filter(_.ext == "aar")
      .distinct

    // TODO do it in some shared location, otherwise each module is doing the same, having its own copy for nothing
    extractAarFiles(aarFiles, Task.dest)
  }

  /**
   * Extracts JAR files and resources from AAR runtime dependencies.
   */
  def androidRuntimeUnpackedArchives: T[Seq[UnpackedDep]] = Task {
    // The way Android is handling configurations for dependencies is a bit different from canonical Maven: it has
    // `api` and `implementation` configurations. If dependency is `api` dependency, it will be exposed to consumers
    // of the library, but if it is `implementation`, then it won't. The simplest analogy is api = compile,
    // implementation = runtime, but both are actually used for compilation and packaging of the final DEX.
    // More here https://docs.gradle.org/current/userguide/java_library_plugin.html#sec:java_library_separation.
    //
    // In Gradle terms using only `resolvedRunMvnDeps` won't be complete, because source modules can be also
    // api/implementation, but Mill has no such configurations.
    val aarFiles = androidOriginalRunClasspath()
      .map(_.path)
      .filter(_.ext == "aar")
      .distinct

    // TODO do it in some shared location, otherwise each module is doing the same, having its own copy for nothing
    extractAarFiles(aarFiles, Task.dest)
  }

  final def extractAarFiles(aarFiles: Seq[os.Path], taskDest: os.Path): Seq[UnpackedDep] = {
    aarFiles.map(aarFile => {
      val extractDir = taskDest / aarFile.baseName
      os.unzip(aarFile, extractDir)
      val name = aarFile.baseName

      def pathOption(p: os.Path): Option[PathRef] = if (os.exists(p)) {
        Some(PathRef(p))
      } else None

      val classesJar = pathOption(extractDir / "classes.jar")
      val proguardRules = pathOption(extractDir / "proguard.txt")
      val androidResources = pathOption(extractDir / "res")
      val manifest = pathOption(extractDir / "AndroidManifest.xml")
      val lintJar = pathOption(extractDir / "lint.jar")
      val metaInf = pathOption(extractDir / "META-INF")
      val nativeLibs = pathOption(extractDir / "jni")
      val baselineProfile = pathOption(extractDir / "baseline-prof.txt")
      val stableIdsRFile = pathOption(extractDir / "R.txt")
      val publicResFile = pathOption(extractDir / "public.txt")
      UnpackedDep(
        name,
        classesJar,
        proguardRules,
        androidResources,
        manifest,
        lintJar,
        metaInf,
        nativeLibs,
        baselineProfile,
        stableIdsRFile,
        publicResFile
      )
    })
  }

  def androidManifestMergerModuleRef: ModuleRef[AndroidManifestMerger] =
    ModuleRef(AndroidManifestMerger)

  def androidMergeableManifests: Task[Seq[PathRef]] = Task {
    androidUnpackedArchives().flatMap(_.manifest)
  }

  def androidMergedManifestArgs: Task[Seq[String]] = Task {
    Seq(
      "--main",
      androidManifest().path.toString(),
      "--remove-tools-declarations",
      "--property",
      s"min_sdk_version=${androidMinSdk()}",
      "--property",
      s"target_sdk_version=${androidTargetSdk()}",
      "--property",
      s"version_code=${androidVersionCode()}",
      "--property",
      s"version_name=${androidVersionName()}"
    ) ++ androidMergeableManifests().flatMap(m => Seq("--libs", m.path.toString()))
  }

  /**
   * Creates a merged manifest from application and dependencies manifests using.
   * Merged manifests are given via [[androidMergeableManifests]] and merger args via
   * [[androidMergedManifestArgs]]
   *
   * See [[https://developer.android.com/build/manage-manifests]] for more details.
   */
  def androidMergedManifest: T[PathRef] = Task {

    val mergedAndroidManifestLocation = androidManifestMergerModuleRef().androidMergedManifest(
      args = Task.Anon(androidMergedManifestArgs())
    )()

    val mergedAndroidManifestDest = Task.dest / "AndroidManifest.xml"

    os.move(mergedAndroidManifestLocation, mergedAndroidManifestDest)

    PathRef(mergedAndroidManifestDest)
  }

  /**
   * Namespace of the Android module.
   * Used in manifest package and also used as the package to place the generated R sources
   */
  def androidNamespace: String

  /**
   * The android resources from the dependencies needed to run the application
   * @return
   */
  def androidRunLibResources: T[PathRef] = Task {
    val out = Task.dest / "lib-res"
    os.makeDir(out)
    for (unpackedDep <- androidRuntimeUnpackedArchives()) {
      val outDir = out / s"${unpackedDep.name}"

      os.makeDir(outDir)

      unpackedDep.manifest.map(_.path).foreach(
        os.copy(_, outDir / "AndroidManifest.xml")
      )
      unpackedDep.androidResources.map {
        androidResPathRef =>
          Seq(
            androidSdkModule().aapt2Path().path.toString(),
            "compile",
            "--dir",
            androidResPathRef.path.toString,
            "-o",
            outDir.toString
          )
      }.foreach(os.call(_))
    }
    PathRef(out)
  }

  def androidLinkedRunLibResources: T[AndroidRes] = Task {
    val compiledLibResources = androidRunLibResources()
    val javaDest = Task.dest / "generatedSources"
    val linkedResDir = Task.dest / "apks"
    os.makeDir(linkedResDir)

    os.makeDir(javaDest)
    val linkArgs = Seq(
      androidSdkModule().aapt2Path().path.toString,
      "link",
      "-I",
      androidSdkModule().androidJarPath().path.toString,
      "--auto-add-overlay",
      "--min-sdk-version",
      androidMinSdk().toString,
      "--target-sdk-version",
      androidTargetSdk().toString,
      "--version-code",
      androidVersionCode().toString,
      "--version-name",
      androidVersionName(),
      "--proguard-conditional-keep-rules"
    )
    val libDirs = os.list(compiledLibResources.path).filter(os.isDir).filter(
      os.list(_).exists(_.ext == "flat")
    )

    libDirs.foreach {
      libDir =>
        val flatFiles = os.list(libDir).filter(_.ext == "flat")
        val out = linkedResDir / s"${libDir.last}.apk"
        val manifest = libDir / "AndroidManifest.xml"
        val args = linkArgs ++
          Seq(
            "--manifest",
            manifest.toString,
            "--java",
            javaDest.toString
          ) ++
          flatFiles.flatMap(f => Seq("-R", f.toString())) ++ Seq("-o", out.toString)
        os.call(args).out.text()
    }

    AndroidRes(buildDir = PathRef(linkedResDir), generatedSources = PathRef(javaDest))

  }

  def androidCompiledLibResources = Task {
    val out = Task.dest / "lib-res"
    os.makeDir(out)
    for (unpackedDep <- androidUnpackedArchives()) {
      val outDir = out / s"${unpackedDep.name}"

      os.makeDir(outDir)

      unpackedDep.manifest.map(_.path).foreach(
        os.copy(_, outDir / "AndroidManifest.xml")
      )
      unpackedDep.androidResources.map {
        androidResPathRef =>
          Seq(
            androidSdkModule().aapt2Path().path.toString(),
            "compile",
            "--dir",
            androidResPathRef.path.toString,
            "-o",
            outDir.toString
          )
      }.foreach(os.call(_))
    }
    PathRef(out)
  }

  def androidLinkedLibResources: T[AndroidRes] = Task {
    val compiledLibResources = androidCompiledLibResources()
    val javaDest = Task.dest / "generatedSources"
    val linkedResDir = Task.dest / "apks"
    os.makeDir(linkedResDir)

    os.makeDir(javaDest)
    val linkArgs = Seq(
      androidSdkModule().aapt2Path().path.toString,
      "link",
      "-I",
      androidSdkModule().androidJarPath().path.toString,
      "--auto-add-overlay",
      "--min-sdk-version",
      androidMinSdk().toString,
      "--target-sdk-version",
      androidTargetSdk().toString,
      "--version-code",
      androidVersionCode().toString,
      "--version-name",
      androidVersionName(),
      "--proguard-conditional-keep-rules"
    )
    val libDirs = os.list(compiledLibResources.path).filter(os.isDir).filter(
      os.list(_).exists(_.ext == "flat")
    )

    libDirs.foreach {
      libDir =>
        val flatFiles = os.list(libDir).filter(_.ext == "flat")
        val out = linkedResDir / s"${libDir.last}.apk"
        val manifest = libDir / "AndroidManifest.xml"
        val args = linkArgs ++
          Seq(
            "--manifest",
            manifest.toString,
            "--java",
            javaDest.toString
          ) ++
          flatFiles.flatMap(f => Seq("-R", f.toString())) ++ Seq("-o", out.toString)
        os.call(args).out.text()
    }

    AndroidRes(buildDir = PathRef(linkedResDir), generatedSources = PathRef(javaDest))

  }

  /**
   * Compiles [[androidResources]] and generates the flat files and R.java
   *
   * @return AndroidRes with PathRef of the path that contains lib-res and generatedSources
   */
  def androidCompiledResources: T[PathRef] = Task {

    val compiledResDir = Task.dest / "lib-res"
    os.makeDir(compiledResDir)
    val compiledResources = collection.mutable.Buffer[os.Path]()

    val localResources =
      androidResources().map(_.path).filter(os.exists)

    for (resDir <- localResources) {

      val compileArgsBuilder = Seq.newBuilder[String]
      compileArgsBuilder ++= Seq(androidSdkModule().aapt2Path().path.toString(), "compile")
      if (Task.log.debugEnabled) {
        compileArgsBuilder += "-v"
      }

      compileArgsBuilder ++= Seq(
        "--dir",
        resDir.toString(),
        "-o",
        compiledResDir.toString()
      )

      if (androidIsDebug()) {
        compileArgsBuilder += "--no-crunch"
      }

      os.call(compileArgsBuilder.result())
    }

    PathRef(compiledResDir)

  }

  def androidTransitiveCompiledResources: T[Seq[PathRef]] = Task {
    Task.traverse(transitiveModuleCompileModuleDeps) {
      case m: AndroidModule =>
        Task.Anon(Seq(m.androidCompiledResources()))
      case _ =>
        Task.Anon(Seq.empty[PathRef])
    }().flatten
  }

  /**
   * Creates an apk of the compiled [[androidResources]] fetched from [[androidCompiledResources]]
   *
   * @return
   */
  def androidCompiledResourcesApk: T[AndroidRes] = Task {

    val apkDestDir = Task.dest / "apk"
    os.makeDir(apkDestDir)
    val apkDest = apkDestDir / "local-res.apk"
    val generatedSourcesDest = Task.dest / "generatedSources"
    os.makeDir(generatedSourcesDest)

    val linkArgs = Seq(
      androidSdkModule().aapt2Path().path.toString,
      "link",
      "-I",
      androidSdkModule().androidJarPath().path.toString,
      "--custom-package",
      androidNamespace,
      "--auto-add-overlay",
      "--min-sdk-version",
      androidMinSdk().toString,
      "--target-sdk-version",
      androidTargetSdk().toString,
      "--version-code",
      androidVersionCode().toString,
      "--version-name",
      androidVersionName(),
      "--proguard-conditional-keep-rules"
    )
    val flatFiles =
      (androidCompiledResources() +: androidTransitiveCompiledResources()).flatMap(pr =>
        os.walk(pr.path)
      ).filter(
        _.ext == "flat"
      )

    val args = linkArgs ++ Seq(
      "--manifest",
      androidManifest().path.toString,
      "--java",
      generatedSourcesDest.toString
    ) ++ flatFiles.flatMap(f => Seq("-R", f.toString())) ++ Seq("-o", apkDest.toString)

    os.call(args).out.text()

    AndroidRes(buildDir = PathRef(apkDestDir), generatedSources = PathRef(generatedSourcesDest))

  }

  /**
   * Creates an intermediate R.jar that includes all the resources from the application and its dependencies.
   */
  def androidProcessedResources: T[PathRef] = Task {

    val sources = Seq(
      androidRunLibResources().path,
      androidCompiledResources().path
    )

    val rJar = Task.dest / "R.jar"

    val classesDest = jvmWorker()
      .worker()
      .compileJava(
        upstreamCompileOutput = upstreamCompileOutput(),
        sources = sources,
        compileClasspath = Seq.empty,
        javacOptions = javacOptions() ++ mandatoryJavacOptions(),
        reporter = Task.reporter.apply(hashCode),
        reportCachedProblems = zincReportCachedProblems(),
        incrementalCompilation = zincIncrementalCompilation()
      ).get.classes.path

    os.zip(rJar, Seq(classesDest))

    PathRef(rJar)
  }

  /** All individual classfiles inherited from the classpath that will be included into the dex */
  def androidPackagedClassfiles: T[Seq[PathRef]] = Task {
    compileClasspath()
      .map(_.path).filter(os.isDir)
      .flatMap(os.walk(_))
      .filter(os.isFile)
      .filter(_.ext == "class")
      .map(PathRef(_))
  }

  def androidPackagedCompiledClasses: T[Seq[PathRef]] = Task {
    os.walk(compile().classes.path)
      .filter(_.ext == "class")
      .map(PathRef(_))
  }

  def androidPackagedDeps: T[Seq[PathRef]] = Task {
    compileClasspath()
      .filter(_ != androidSdkModule().androidJarPath())
      .filter(_.path.ext == "jar")
  }

  /** Additional library classes provided */
  def libraryClassesPaths: T[Seq[PathRef]] = Task {
    androidSdkModule().androidLibsClasspaths()
  }

  /** Optional baseline profile for ART rewriting */
  def baselineProfile: T[Option[PathRef]] = Task {
    None
  }

}
