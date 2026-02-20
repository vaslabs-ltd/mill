package rest;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Path("/todos")
public class Todos {

  private static final List<Todo> list = new CopyOnWriteArrayList<>();

  @CheckedTemplate
  static class Templates {
    public static native TemplateInstance index(List<Todo> todos);
  }

  @GET
  @Produces(MediaType.TEXT_HTML)
  public TemplateInstance index() {
    return Templates.index(list);
  }

  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public TemplateInstance add(@FormParam("task") String task) {
    list.add(new Todo(task, false));
    return index();
  }
}
