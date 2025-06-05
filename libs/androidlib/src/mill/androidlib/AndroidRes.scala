package mill.androidlib

import mill.PathRef

case class AndroidRes(buildDir: PathRef, generatedSources: PathRef)

object AndroidRes:
  given resultRW: upickle.default.ReadWriter[AndroidRes] = upickle.default.macroRW
