package rest;

// Server-side templating engine.
// See: https://quarkus.io/guides/qute
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.transaction.Transactional;

// Define REST endpoints and handle HTTP requests.
// See: https://quarkus.io/guides/rest
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/todos")
public class Todos {

  @CheckedTemplate
  static class Templates {
    public static native TemplateInstance index(List<Todo> todos);
  }

  @GET
  @Produces(MediaType.TEXT_HTML)
  public TemplateInstance index() {
    return Templates.index(Todo.listAll());
  }

  @POST
  @Transactional
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public TemplateInstance add(@FormParam("task") String task) {
    var todo = new Todo(task, false);
    todo.persist();
    return index();
  }
}
