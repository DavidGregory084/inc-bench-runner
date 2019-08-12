package inc.benchmark

import com.github.tototoshi.csv._
import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection
import java.io.FileReader
import java.nio.file.Paths
import java.sql.DriverManager
import java.time._
import java.time.format._
import scala.util.control.NonFatal

object Runner {
  def main(args: Array[String]): Unit = {

    var benchmarkDir = os.pwd
    var incDir = os.pwd

    val parser = new scopt.OptionParser[Unit]("inc") {
      head("benchmark-inc")

      help("help")

      opt[String]("inc-repo-dir")
        .abbr("i")
        .text("The path to the directory containing the inc repo.")
        .validate { i =>
          try {
            os.Path(i)
            success
          } catch {
            case NonFatal(e) =>
              failure(s"The inc repo folder $i is not valid: ${e.getMessage}")
          }
        }.foreach { i =>
          incDir = os.Path(i)
        }

      opt[String]("benchmark-dir")
        .abbr("b")
        .text("The path to the directory containing benchmark files to compile.")
        .validate { b =>
          try {
            os.Path(b)
            success
          } catch {
            case NonFatal(e) =>
              failure(s"The benchmark folder $b is not valid: ${e.getMessage}")
          }
        }.foreach { b =>
          benchmarkDir = os.Path(b)
        }
    }

    parser.parse(args, ()).foreach { _ =>
      runBenchmark(incDir, benchmarkDir)
      writeBenchmarkCsv(benchmarkDir)
    }
  }

  def runBenchmark(incDir: os.Path, benchmarkDir: os.Path) = {
    def currentBranchName(): String = {
      os.proc('git, "rev-parse", "--abbrev-ref", 'HEAD)
        .call(incDir)
        .out.string.trim
    }

    def currentCommitRef(): String = {
      os.proc('git, "rev-parse", 'HEAD)
        .call(incDir)
        .out.string.trim
    }

    def currentCommitTime(): ZonedDateTime = {
      val result = os.proc(
        'git, 'show, "-s",
        "--format=%cd",
        "--date=iso",
        currentCommitRef()
      ).call(incDir)

      val timeStr = result.out.string.trim
      val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss ZZZ")
      ZonedDateTime.parse(timeStr, formatter).withZoneSameInstant(ZoneOffset.UTC)
    }

    def addRunData(baseName: String, time: ZonedDateTime): Unit = {
      val path = benchmarkDir / (baseName + ".json")
      val json = ujson.read(os.read(path))
      json("results")(0)("executionTime") = time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
      json("results")(0)("commitRef") = currentCommitRef()
      json("results")(0)("branchName") = currentBranchName()
      json("results")(0)("commitTime") = currentCommitTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
      os.write.over(path, ujson.write(json, indent = 2))
    }

    def runHyperfine(src: os.Path): Unit = {
      println(s"Running benchmark $src")

      val executionTime = ZonedDateTime.now(ZoneOffset.UTC)

      val assemblyJar = incDir / "out" / "main" / "assembly" / "dest" / "out.jar"

      os.proc(
        'hyperfine,
        s"--show-output",
        s"--export-json",
        s"${benchmarkDir / src.baseName}.json",
        s"java -jar ${assemblyJar} ${src}"
      ).stream(
        cwd = benchmarkDir,
        onOut = (buf, len) => println(new String(buf.slice(0, len))),
        onErr= (buf, len) => println(new String(buf.slice(0, len)))
      )

      addRunData(src.baseName, executionTime)
    }

    println(s"Running benchmarks in $benchmarkDir")

    if (benchmarkDir.toIO.exists) {
      os.walk(benchmarkDir)
        .filter(_.last.matches(".*.inc"))
        .foreach(runHyperfine)
    }
  }

  def writeBenchmarkCsv(benchmarkDir: os.Path) = {
    os.walk(benchmarkDir)
      .filter(_.last.matches(".*.json"))
      .map { src =>
        val csvFormat = new DefaultCSVFormat { override val quoting = QUOTE_NONE }
        val outFile = benchmarkDir / (src.baseName + ".csv")
        val writer = CSVWriter.open(outFile.toIO)(csvFormat)
        val str = os.read(src)
        val json = ujson.read(str)

        writer.writeRow(List(
          "execution_time",
          "branch_name",
          "commit_ref",
          "commit_time",
          "benchmark_name",
          "benchmark_type",
          "compilation_mode",
          "measurement"
        ))

        json("results")(0)("times").arr.foreach { measurement =>
          writer.writeRow(List(
            json("results")(0)("executionTime").str,
            json("results")(0)("branchName").str,
            json("results")(0)("commitRef").str,
            json("results")(0)("commitTime").str,
            src.baseName,
            "duration",
            "command_line_interface",
            (measurement.num * 1000).toInt
          ))
        }

      writer.close()
    }
  }

  def publishBenchmarkCsv(benchmarkDir: os.Path) = {
    val benchmarkDbUrl = sys.env("INC_BENCHMARK_DB_URL")
    val benchmarkDbUser = sys.env("INC_BENCHMARK_DB_USER")
    val benchmarkDbPassword = sys.env("INC_BENCHMARK_DB_PASSWORD")

    Class.forName("org.postgresql.Driver")
    val conn = DriverManager.getConnection(benchmarkDbUrl, benchmarkDbUser, benchmarkDbPassword)
    val copyManager = new CopyManager(conn.asInstanceOf[BaseConnection])

    if (benchmarkDir.toIO.exists) {
      os.walk(benchmarkDir)
        .filter(_.last.matches(".*.csv"))
        .foreach { csv =>
          print(s"Uploading benchmark data from $csv")
          val reader = new FileReader(csv.toIO)
          copyManager.copyIn("""copy benchmark_results from stdin with csv header quote '"'""", reader)
        }
    }

    conn.close()
  }
}