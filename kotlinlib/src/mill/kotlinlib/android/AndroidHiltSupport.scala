package mill.kotlinlib.android

import mill.T
import mill.api.PathRef
import mill.kotlinlib.ksp.KspModule
import mill.{T, Task}

trait AndroidHiltSupport extends AndroidAppKotlinModule with KspModule {

  override def kspClasspath: T[Seq[PathRef]] =
   Seq(androidProcessResources()) ++ super.kspClasspath()

  def kotlincOptions = Task {
    super.kotlincOptions() ++
      Seq(
        "-P", "plugin:com.google.devtools.ksp.symbol-processing:apoption=dagger.fastInit=enabled",
        "-P", "plugin:com.google.devtools.ksp.symbol-processing:apoption=dagger.hilt.android.internal.disableAndroidSuperclassValidation=true",
        "-P", "plugin:com.google.devtools.ksp.symbol-processing:apoption=dagger.hilt.android.internal.projectType=APP",
        "-P", "plugin:com.google.devtools.ksp.symbol-processing:apoption=dagger.hilt.internal.useAggregatingRootProcessor=false",
        "-opt-in=kotlin.RequiresOptIn",
        "-opt-in=kotlin.Experimental",
        "-opt-in=kotlin.RequiresOptIn",
        "-opt-in=kotlin.Experimental"
      )
  }
}
