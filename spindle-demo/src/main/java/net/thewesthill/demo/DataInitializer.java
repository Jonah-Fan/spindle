package net.thewesthill.demo;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements ApplicationRunner {

  private final UserRepository repo;

  public DataInitializer(UserRepository repo) {
    this.repo = repo;
  }

  @Override
  public void run(@NonNull ApplicationArguments args) {
    repo.save(new User(1L, "Alice", "alice@example.com"));
    repo.save(new User(2L, "bob", "bob@example.com"));
    repo.save(new User(3L, "carol", "carol@example.com"));
  }
}
