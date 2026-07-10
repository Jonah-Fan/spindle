package net.thewesthill.demo;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.NonNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class UserController {

  private final UserService userService;
  private final ExecutorService pool = Executors.newFixedThreadPool(4);

  public UserController(UserService userService) {
    this.userService = userService;
  }

  @GetMapping("/{id}")
  public User get(@PathVariable @NonNull Long id) {
    userService.doBusinessWork();
    return userService.findById(id);
  }

  @GetMapping("/{id}/error")
  public User error(@PathVariable @NonNull Long id) {
    return userService.findByIdOrFail(id);
  }

  @GetMapping("/{id}/async")
  public String async(@PathVariable @NonNull Long id) throws Exception {
    var future =
        pool.submit(
            () -> {
              userService.doBusinessWork();
              return userService.findById(id);
            });
    User u = future.get(2, TimeUnit.SECONDS);
    return "async: " + (u == null ? "null" : u.getName());
  }
}
