package scalajsesbuild

import scalajsesbuild.EsbuildScalaJSModuleConfiguration.EsbuildPlatform

final class EsbuildScalaJSModuleConfiguration(val platform: EsbuildPlatform) {
  override def toString: String = {
    s"EsbuildScalaJSModuleConfiguration($platform)"
  }
}

object EsbuildScalaJSModuleConfiguration {
  sealed trait EsbuildPlatform
  object EsbuildPlatform {
    case object Browser extends EsbuildPlatform
    case object Node extends EsbuildPlatform
    case object Neutral extends EsbuildPlatform
  }
}
