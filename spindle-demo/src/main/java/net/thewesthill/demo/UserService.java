package net.thewesthill.demo;

import net.thewesthill.trace.Trace;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

@Service
public class UserService {

  private final UserRepository userRepository;

  public UserService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  @Trace("UserService.findById")
  public User findById(@NonNull Long id) {
    validateId(id);
    return userRepository.findById(id).orElse(null);
  }

  @Trace("UserService.validateId")
  public void validateId(@NonNull Long id) {
    if (id <= 0L) {
      throw new IllegalArgumentException("invalid id: " + id);
    }
  }

  @Trace("UserService.findByIdOrFail")
  public User findByIdOrFail(@NonNull Long id) {
    return userRepository
        .findById(id)
        .orElseThrow(() -> new IllegalStateException("user " + id + " not found"));
  }

  @Trace
  public void doBusinessWork() {
    try {
      Thread.sleep(20);
    } catch (InterruptedException cause) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Task Interrupt.", cause);
    }
  }
}
