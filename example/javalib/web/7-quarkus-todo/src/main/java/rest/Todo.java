package rest;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class Todo {
  public String task;
  public boolean completed;

  public Todo() {}

  public Todo(String task, boolean completed) {
    this.task = task;
    this.completed = completed;
  }
}
