package rest;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class TodosTest {

  @Test
  public void testTodoPage() {
    Response response = given().when().get("/todos").then().extract().response();

    assertEquals(200, response.statusCode());

    assertEquals("text/html;charset=UTF-8", response.contentType());

    String body = response.asString();
    assertTrue(body.contains("<h1>My Todo List</h1>"), "The header should be present in the HTML");
  }

  @Test
  public void testAddTodo() {
    Response response = given()
        .contentType("application/x-www-form-urlencoded")
        .formParam("task", "Buy milk")
        .when()
        .post("/todos")
        .then()
        .extract()
        .response();

    assertEquals(200, response.statusCode());

    String body = response.asString();
    assertTrue(body.contains("Buy milk"), "The new task should be present in the HTML");

    String newResponseBody =
        given().when().get("/todos").then().extract().response().asString();

    assertTrue(
        newResponseBody.contains("Buy milk"),
        "The new task should be present in the HTML after adding");
  }
}
