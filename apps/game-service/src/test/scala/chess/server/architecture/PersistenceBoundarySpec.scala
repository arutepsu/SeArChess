package chess.server.architecture

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class PersistenceBoundarySpec extends AnyFlatSpec with Matchers:

  private val root = Path.of(System.getProperty("user.dir"))

  "game-service main sources" should "not import Slick directly" in {
    val offenders =
      scalaMainFiles
        .filterNot(isSlickOrPostgresAdapter)
        .filter(containsAny(_, SlickForbidden))

    offenders shouldBe empty
  }

  they should "not import Mongo or BSON directly outside the Mongo adapter/runtime" in {
    val offenders =
      scalaMainFiles
        .filterNot(isMongoAdapterOrRuntime)
        .filter(containsAny(_, MongoForbidden))

    offenders shouldBe empty
  }

  private def scalaMainFiles: List[Path] =
    val sourceRoots = List(
      root.resolve("apps/game-service/src/main/scala"),
      root.resolve("apps/game-service/modules/core/src/main/scala"),
      root.resolve("apps/game-service/modules/migration/src/main/scala"),
      root.resolve("apps/game-service/modules/persistence/src/main/scala")
    )

    sourceRoots
      .filter(Files.exists(_))
      .flatMap { sourceRoot =>
        val stream = Files.walk(sourceRoot)
        try stream.iterator().asScala.filter(path => path.toString.endsWith(".scala")).toList
        finally stream.close()
      }

  private def containsAny(path: Path, fragments: List[String]): Boolean =
    val content = Files.readString(path)
    fragments.exists(content.contains)

  private def isSlickOrPostgresAdapter(path: Path): Boolean =
    val value = normalized(path)
    value.contains("/chess/adapter/repository/slick/") ||
      value.contains("/chess/adapter/repository/postgres/")

  private def isMongoAdapterOrRuntime(path: Path): Boolean =
    val value = normalized(path)
    value.contains("/chess/adapter/repository/mongo/") ||
      value.endsWith("/chess/server/persistence/MongoPersistenceRuntime.scala")

  private def normalized(path: Path): String =
    root.relativize(path).toString.replace('\\', '/')

  private val SlickForbidden = List(
    "import slick.",
    "slick.jdbc.PostgresProfile",
    "slick.jdbc.JdbcProfile",
    "DBIO",
    "TableQuery",
    "PostgresProfile"
  )

  private val MongoForbidden = List(
    "import com.mongodb",
    "import org.bson",
    "MongoClient",
    "MongoCollection",
    "Document"
  )
