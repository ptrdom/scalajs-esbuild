package scalajsesbuild

class EsbuildElectronProcessConfiguration(
    val mainModuleID: String,
    val preloadModuleIDs: Set[String],
    val rendererModuleIDs: Set[String]
) {
  override def toString: String = {
    s"EsbuildElectronProcessConfiguration($mainModuleID,$preloadModuleIDs,$rendererModuleIDs)"
  }
}

object EsbuildElectronProcessConfiguration {
  def main(moduleID: String): EsbuildElectronProcessConfiguration =
    new EsbuildElectronProcessConfiguration(moduleID, Set.empty, Set.empty)
}
