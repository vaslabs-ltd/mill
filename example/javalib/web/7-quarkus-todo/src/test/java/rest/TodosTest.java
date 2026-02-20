package rest;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class TodosTest {

  @Test
  public void testTodoPage() {
    Response response = given()
      .when().get("/todos")
      .then()
      .extract().response();

    assertEquals(200, response.statusCode());

    assertEquals("text/html;charset=UTF-8", response.contentType());

    String body = response.asString();
    assertTrue(body.contains("<h1>My Todo List</h1>"), "The header should be present in the HTML");
  }
}
