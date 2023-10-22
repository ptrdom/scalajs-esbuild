package scalajsesbuild

import scalajsesbuild.EsbuildScalaJSModuleConfiguration.EsbuildPlatform

final class EsbuildScalaJSModuleConfiguration(val platform: EsbuildPlatform)

object EsbuildScalaJSModuleConfiguration {
  sealed trait EsbuildPlatform
  object EsbuildPlatform {
    case object Browser extends EsbuildPlatform
    case object Node extends EsbuildPlatform
  }
}
