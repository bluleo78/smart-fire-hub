package com.smartfirehub.global.exception;

import com.smartfirehub.ai.exception.AiSessionNotFoundException;
import com.smartfirehub.auth.exception.AccountLockedException;
import com.smartfirehub.auth.exception.EmailAlreadyExistsException;
import com.smartfirehub.auth.exception.InvalidCredentialsException;
import com.smartfirehub.auth.exception.InvalidTokenException;
import com.smartfirehub.auth.exception.UsernameAlreadyExistsException;
import com.smartfirehub.dataimport.exception.ImportValidationException;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.pipeline.exception.CyclicDependencyException;
import com.smartfirehub.pipeline.exception.PipelineNotFoundException;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.exception.TriggerNotFoundException;
import com.smartfirehub.role.exception.RoleNotFoundException;
import com.smartfirehub.role.exception.SystemRoleModificationException;
import com.smartfirehub.user.exception.UserNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test/exception")
public class ExceptionStubController {

  public record ValidatedBody(@NotBlank String name) {}

  @GetMapping("/username-exists")
  public void usernameExists() {
    throw new UsernameAlreadyExistsException("Username already exists");
  }

  @GetMapping("/email-exists")
  public void emailExists() {
    throw new EmailAlreadyExistsException("Email already exists");
  }

  @GetMapping("/invalid-credentials")
  public void invalidCredentials() {
    throw new InvalidCredentialsException("Invalid credentials");
  }

  @GetMapping("/invalid-token")
  public void invalidToken() {
    throw new InvalidTokenException("Invalid token");
  }

  @GetMapping("/access-denied")
  public void accessDenied() {
    throw new AccessDeniedException("Access denied");
  }

  @GetMapping("/user-not-found")
  public void userNotFound() {
    throw new UserNotFoundException("User not found");
  }

  @GetMapping("/role-not-found")
  public void roleNotFound() {
    throw new RoleNotFoundException("Role not found");
  }

  @GetMapping("/dataset-not-found")
  public void datasetNotFound() {
    throw new DatasetNotFoundException("Dataset not found");
  }

  @GetMapping("/pipeline-not-found")
  public void pipelineNotFound() {
    throw new PipelineNotFoundException("Pipeline not found");
  }

  @GetMapping("/trigger-not-found")
  public void triggerNotFound() {
    throw new TriggerNotFoundException("Trigger not found");
  }

  @GetMapping("/ai-session-not-found")
  public void aiSessionNotFound() {
    throw new AiSessionNotFoundException(99L);
  }

  @GetMapping("/illegal-argument")
  public void illegalArgument() {
    throw new IllegalArgumentException("Illegal argument");
  }

  @GetMapping("/system-role-modification")
  public void systemRoleModification() {
    throw new SystemRoleModificationException("Cannot modify system role");
  }

  @GetMapping("/import-validation")
  public void importValidation() {
    throw new ImportValidationException(
        "Import validation failed", List.of("Row 1: missing value", "Row 2: invalid type"));
  }

  @GetMapping("/cyclic-dependency")
  public void cyclicDependency() {
    throw new CyclicDependencyException("Cyclic dependency detected");
  }

  @GetMapping("/script-execution")
  public void scriptExecution() {
    throw new ScriptExecutionException("Script execution failed");
  }

  @GetMapping("/data-integrity-violation")
  public void dataIntegrityViolation() {
    throw new DataIntegrityViolationException("Integrity error");
  }

  @GetMapping("/account-locked")
  public void accountLocked() {
    throw new AccountLockedException("Too many failed login attempts. Please try again later.");
  }

  @GetMapping("/unexpected-error")
  public void unexpectedError() {
    throw new RuntimeException("Something unexpected happened");
  }

  @PostMapping("/method-argument-not-valid")
  public void methodArgumentNotValid(@RequestBody @Valid ValidatedBody body) {
    // validation failure is triggered by Spring before reaching here
  }
}
