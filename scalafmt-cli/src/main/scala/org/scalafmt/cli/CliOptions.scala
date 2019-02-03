package org.scalafmt.cli

import java.io.InputStream
import java.io.PrintStream
import java.nio.charset.UnsupportedCharsetException
import java.nio.file.Path

import com.typesafe.config.{ConfigException, ConfigFactory}
import org.scalafmt.util.{AbsoluteFile, GitOps, GitOpsImpl, OsSpecific}

import scala.io.Codec
import scala.util.control.NonFatal
import scala.util.matching.Regex

object CliOptions {
  val default = CliOptions()

  /**
    * Tries to read configuration from
    *
    * 1. .scalafmt.conf in root dir of current git repo
    *     IF the following setting is enabled: project.git = true
    * 2. .scalafmt.conf from init.commong.workingDirectory
    *
    * I am happy to add alternative fallback methods for other VCS.
    *
    * WARNING. Throws an exception if the .scalafmt.conf error exists but
    * contains an error. Why? Because this method is only supposed to be
    * called directly from main.
    */
  def auto(args: Array[String], init: CliOptions)(
      parsed: CliOptions): CliOptions = {
    val style: Option[Path] = if (init.config != parsed.config) {
      parsed.config
    } else {
      tryCurrentDirectory(parsed).orElse(tryGit(parsed))
    }
    val newMode = if (parsed.testing) Stdout else parsed.writeMode
    parsed.copy(
      writeMode = newMode,
      config = style
    )
  }

  private def getConfigJFile(file: AbsoluteFile): AbsoluteFile =
    file / ".scalafmt.conf"

  private def tryDirectory(options: CliOptions)(dir: AbsoluteFile): Path =
    getConfigJFile(dir).jfile.toPath

  private def tryGit(options: CliOptions): Option[Path] = {
    for {
      rootDir <- options.gitOps.rootDir
      path = tryDirectory(options)(rootDir)
      configFilePath <- if (path.toFile.isFile) Some(path) else None
    } yield configFilePath
  }

  private def tryCurrentDirectory(options: CliOptions): Option[Path] = {
    val configFilePath = tryDirectory(options)(options.common.workingDirectory)
    if (configFilePath.toFile.isFile) Some(configFilePath) else None
  }
}

case class CommonOptions(
    workingDirectory: AbsoluteFile = AbsoluteFile.userDir,
    out: PrintStream = System.out,
    in: InputStream = System.in,
    err: PrintStream = System.err
)

case class CliOptions(
    config: Option[Path] = None,
    range: Set[Range] = Set.empty[Range],
    customFiles: Seq[AbsoluteFile] = Nil,
    customExcludes: Seq[String] = Nil,
    writeMode: WriteMode = Override,
    testing: Boolean = false,
    stdIn: Boolean = false,
    quiet: Boolean = false,
    debug: Boolean = false,
    git: Option[Boolean] = None,
    nonInteractive: Boolean = false,
    diff: Option[String] = None,
    assumeFilename: String = "stdin.scala", // used when read from stdin
    migrate: Option[AbsoluteFile] = None,
    common: CommonOptions = CommonOptions(),
    gitOpsConstructor: AbsoluteFile => GitOps = x => new GitOpsImpl(x),
    noStdErr: Boolean = false
) {
  private[this] val DefaultGit = false
  private[this] val DefaultFatalWarnings = false
  private[this] val DefaultIgnoreWarnings = false
  private[this] val DefaultEncoding = Codec.UTF8

  def configPath: Path = config.getOrElse(
    (common.workingDirectory / ".scalafmt.conf").jfile.toPath
  )

  val inPlace: Boolean = writeMode == Override

  val fileFetchMode: FileFetchMode = {
    diff.map(DiffFiles).getOrElse {
      if (isGit) GitFiles else RecursiveSearch
    }
  }

  val files: Seq[AbsoluteFile] =
    if (customFiles.isEmpty)
      Seq(common.workingDirectory)
    else
      customFiles

  val gitOps: GitOps = gitOpsConstructor(common.workingDirectory)
  /*
  def withProject(projectFiles: ProjectFiles): CliOptions = {
    this.copy(config = config.copy(project = projectFiles))
  }
   */

  def withFiles(files: Seq[AbsoluteFile]): CliOptions = {
    this.copy(customFiles = files)
  }

  def info: PrintStream = {
    if (noStdErr || (!stdIn && writeMode != Stdout)) common.out else common.err
  }

  def excludeFilterRegexp: Regex =
    mkRegexp(customExcludes.map(OsSpecific.fixSeparatorsInPathPattern))

  private def mkRegexp(filters: Seq[String], strict: Boolean = false): Regex =
    filters match {
      case Nil => "$a".r // will never match anything
      case head :: Nil => head.r
      case _ if strict => filters.mkString("^(", "|", ")$").r
      case _ => filters.mkString("(", "|", ")").r
    }

  private[cli] def isGit: Boolean = readGit(configPath).getOrElse(DefaultGit)

  private[cli] def fatalWarnings: Boolean =
    readFatalWarnings(configPath).getOrElse(DefaultFatalWarnings)

  private[cli] def ignoreWarnings: Boolean =
    readIgnoreWarnings(configPath).getOrElse(DefaultIgnoreWarnings)

  private[cli] def onTestFailure: Option[String] = readOnTestFailure(configPath)

  private[cli] def encoding: Codec =
    readEncoding(configPath).getOrElse(DefaultEncoding)

  private def readGit(config: Path): Option[Boolean] = {
    try {
      Some(
        ConfigFactory
          .parseFile(config.toFile)
          .getConfig("project")
          .getBoolean("git"))
    } catch {
      case _: ConfigException.Missing => None
      case NonFatal(_) => None
    }
  }

  private def readOnTestFailure(config: Path): Option[String] = {
    try {
      Some(ConfigFactory.parseFile(config.toFile).getString("onTestFailure"))
    } catch {
      case _: ConfigException.Missing => None
      case NonFatal(_) => None
    }
  }

  private def readFatalWarnings(config: Path): Option[Boolean] = {
    try {
      Some(
        ConfigFactory
          .parseFile(config.toFile)
          .getConfig("runner")
          .getBoolean("fatalWarnings"))
    } catch {
      case _: ConfigException.Missing => None
      case NonFatal(_) => None
    }
  }

  private def readIgnoreWarnings(config: Path): Option[Boolean] = {
    try {
      Some(
        ConfigFactory
          .parseFile(config.toFile)
          .atPath("runner")
          .getBoolean("ignoreWarnings"))
    } catch {
      case _: ConfigException.Missing => None
      case NonFatal(_) => None
    }
  }

  private def readEncoding(config: Path): Option[Codec] = {
    try {
      val codecStr =
        ConfigFactory.parseFile(config.toFile).getString("encoding")
      Some(Codec.apply(codecStr))
    } catch {
      case _: ConfigException.Missing => None
      case _: UnsupportedCharsetException => None
      case NonFatal(_) => None
    }
  }
}
