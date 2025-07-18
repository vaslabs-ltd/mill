package mill.scalalib

import mill.api.Result
import mill.api.Discover
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import mill.util.TokenReaders._
import mill.Task
import mill.T
import os.Path
import sbt.testing.Status
import utest.*

import scala.xml.{Elem, NodeSeq, XML}

object TestRunnerTestUtils {
  object testrunner extends TestRunnerTestModule {
    def computeTestForkGrouping(x: Seq[String]) = Seq(x)
    def enableParallelism = false

    lazy val millDiscover = Discover[this.type]
  }

  object testrunnerGrouping extends TestRunnerTestModule {
    def computeTestForkGrouping(x: Seq[String]) = x.sorted.grouped(2).toSeq
    def enableParallelism = false

    lazy val millDiscover = Discover[this.type]
  }

  object testrunnerWorkStealing extends TestRunnerTestModule {
    def computeTestForkGrouping(x: Seq[String]) = Seq(x)
    def enableParallelism = true

    lazy val millDiscover = Discover[this.type]
  }

  trait TestRunnerTestModule extends TestRootModule with ScalaModule {
    def computeTestForkGrouping(x: Seq[String]): Seq[Seq[String]]
    def enableParallelism: Boolean
    def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)

    object utest extends ScalaTests with TestModule.Utest {
      override def utestVersion = sys.props.getOrElse("TEST_UTEST_VERSION", ???)
      override def testForkGrouping = computeTestForkGrouping(discoveredTestClasses())
      override def testParallelism = enableParallelism
    }

    object scalatest extends ScalaTests with TestModule.ScalaTest {
      override def testForkGrouping = computeTestForkGrouping(discoveredTestClasses())
      override def testParallelism = enableParallelism
      override def mvnDeps = Task {
        super.mvnDeps() ++ Seq(
          mvn"org.scalatest::scalatest:${sys.props.getOrElse("TEST_SCALATEST_VERSION", ???)}"
        )
      }
    }

    trait DoneMessage extends ScalaTests {
      override def mvnDeps = Task {
        super.mvnDeps() ++ Seq(
          mvn"org.scala-sbt:test-interface:${sys.props.getOrElse("TEST_TEST_INTERFACE_VERSION", ???)}"
        )
      }
      override def testParallelism = enableParallelism
    }
    object doneMessageSuccess extends DoneMessage {
      def testFramework = "mill.scalalib.DoneMessageSuccessFramework"
    }
    object doneMessageFailure extends DoneMessage {
      def testFramework = "mill.scalalib.DoneMessageFailureFramework"
      override def discoveredTestClasses = Seq("hello.World")
    }
    object doneMessageNull extends DoneMessage {
      def testFramework = "mill.scalalib.DoneMessageNullFramework"
    }

    object ziotest extends ScalaTests with TestModule.ZioTest {
      override def testForkGrouping = computeTestForkGrouping(discoveredTestClasses())
      override def testParallelism = enableParallelism
      override def zioTestVersion: T[String] = sys.props.getOrElse("TEST_ZIOTEST_VERSION", ???)
    }
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "testrunner"

  class TestOnlyTester(m: TestRunnerTestModule => TestModule) extends AutoCloseable {
    val testers =
      for (mod <- Seq(testrunner, testrunnerGrouping, testrunnerWorkStealing))
        yield (UnitTester(mod, resourcePath), mod)

    def close() = {
      testers.foreach(_._1.closeWithoutCheckingLeaks())
      testers.foreach(_._1.checkLeaks())
    }
    def testOnly0(f: (UnitTester, TestRunnerTestModule) => Unit) = {
      testers.foreach((eval, mod) => f(eval, mod))
    }
    def testOnly(
        args: Seq[String],
        size: Int,
        expectedFileListing: Map[TestModule, Set[String]] = Map()
    ) = {
      testOnly0 { (eval, mod) =>
        val Right(result) = eval.apply(m(mod).testOnly(args*)): @unchecked
        val testOnly = result.value
        if (expectedFileListing.nonEmpty) {
          val dest = eval.outPath / m(mod).toString / "testOnly.dest"
          val sortedListed = os.list(dest).map(_.last).sorted
          val sortedExpected = expectedFileListing(m(mod)).toSeq.sorted
          assert(sortedListed == sortedExpected)
        }
        // Regardless of whether tests are grouped or not, the same
        // number of test results appear at the end
        assert(testOnly.results.size == size)
      }
    }
  }
  trait JUnitReportMatch {
    def shouldHave(statuses: (Int, Status)*): Unit
  }
  def junitReportIn(
      outPath: Path,
      moduleName: String,
      action: String = "testForked"
  ): JUnitReportMatch = {
    val reportPath: Path = outPath / moduleName / s"$action.dest" / "test-report.xml"
    val reportXML = XML.loadFile(reportPath.toIO)
    new JUnitReportMatch {
      override def shouldHave(statuses: (Int, Status)*): Unit = {
        def getValue(attribute: String): Int =
          reportXML.attribute(attribute).map(_.toString).getOrElse("0").toInt
        statuses.foreach { case (expectedQuantity: Int, status: Status) =>
          status match {
            case Status.Success =>
              val testCases: NodeSeq = reportXML \\ "testcase"
              val actualSucceededTestCases: Int =
                testCases.count(tc => !tc.child.exists(n => n.isInstanceOf[Elem]))
              assert(expectedQuantity == actualSucceededTestCases)
            case _ =>
              val statusXML = reportXML \\ status.name().toLowerCase
              val nbSpecificStatusElement = statusXML.size
              assert(expectedQuantity == nbSpecificStatusElement)
              val specificStatusAttributeValue = getValue(s"${status.name().toLowerCase}s")
              assert(expectedQuantity == specificStatusAttributeValue)
          }
        }
        val expectedNbTests = statuses.map(_._1).sum
        val actualNbTests = getValue("tests")
        assert(expectedNbTests == actualNbTests)
      }
    }
  }
}
