package mill.androidlib

import mill.PathRef

case class AndroidRes(apkDir: PathRef, generatedSources: PathRef)

object AndroidRes:
  given resultRW: upickle.default.ReadWriter[AndroidRes] = upickle.default.macroRW