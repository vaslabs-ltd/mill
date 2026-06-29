package mill.main.maven

import utest.*

import java.util.Properties

object ModelerTests extends TestSuite {

  def tests: Tests = Tests {
    test("system properties override model properties for interpolation") {
      val workspace = os.temp.dir(prefix = "mill-maven-modeler-")
      val pom = workspace / "pom.xml"
      os.write.over(
        pom,
        """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>precedence-test</artifactId>
  <version>0.1.0</version>

  <properties>
    <managed.version>1.0.0</managed.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>com.example</groupId>
      <artifactId>demo-dep</artifactId>
      <version>${managed.version}</version>
    </dependency>
  </dependencies>
</project>
"""
      )

      val props = Modeler.defaultSystemProperties(workspace)
      props.put("managed.version", "2.0.0")

      val result = Modeler(workspace, systemProperties = props).build(pom.toIO)
      val resolvedVersion = result.getRawModel.getDependencies.get(0).getVersion

      assert(resolvedVersion == "2.0.0")
    }
  }
}


