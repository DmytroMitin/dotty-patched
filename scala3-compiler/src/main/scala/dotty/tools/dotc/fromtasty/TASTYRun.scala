package dotty.tools
package dotc
package fromtasty

import scala.language.unsafeNulls

import io.{JarArchive, AbstractFile, Path}
import core.Contexts._
import java.io.File

class TASTYRun(comp: Compiler, ictx: Context) extends Run(comp, ictx) {
  override def compile(files: List[AbstractFile]): Unit = {
    val units = tastyUnits(files)
    compileUnits(units)
  }

  private def tastyUnits(files: List[AbstractFile]): List[TASTYCompilationUnit] =
    val fromTastyIgnoreList = ctx.settings.YfromTastyIgnoreList.value.toSet
    // Resolve class names of tasty and jar files
    val classNames = files.flatMap { file =>
      file.extension match
        case "jar" =>
          JarArchive.open(Path(file.path), create = false).allFileNames()
            .map(_.stripPrefix(File.separator)) // change paths from absolute to relative
            .filter(e => Path.extension(e) == "tasty" && !fromTastyIgnoreList(e))
            .map(e => e.stripSuffix(".tasty").replace(File.separator, "."))
            .toList
        case "tasty" => TastyFileUtil.getClassName(file)
        case _ =>
          report.error(s"File extension is not `tasty` or `jar`: ${file.path}")
          Nil
    }
    classNames.map(new TASTYCompilationUnit(_))
}
