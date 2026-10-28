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
import com.smartfirehub.pipeline.exception.TiberoUnqualifiedTableException;
import com.smartfirehub.pipeline.exception.TriggerNotFoundException;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import com.smartfirehub.role.exception.RoleNotFoundException;
import com.smartfirehub.role.exception.SystemRoleModificationException;
import com.smartfirehub.user.exception.UserNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.sql.SQLException;
import java.util.List;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    throw new DatasetNotFoundException();
  }

  @GetMapping("/pipeline-not-found")
  public void pipelineNotFound() {
    throw new PipelineNotFoundException();
  }

  @GetMapping("/trigger-not-found")
  public void triggerNotFound() {
    throw new TriggerNotFoundException();
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

  /**
   * duplicate key / foreign key / check constraint 어디에도 분류되지 않는 제약조건 위반 (#320). 원문 DB 메시지에 테이블·컬럼명이
   * 들어있어, 이것이 사용자 응답으로 새는지 검증하기 위한 스텁이다.
   */
  @GetMapping("/data-integrity-violation-unclassified")
  public void dataIntegrityViolationUnclassified() {
    throw new DataIntegrityViolationException(
        "SQL [insert into ...]; not-null constraint",
        new RuntimeException(
            "ERROR: null value in column \"tenant_id\" of relation \"dataset_mapping\""
                + " violates not-null constraint"));
  }

  /** 분류되는 세 원인(중복/FK/체크) — 사용자 메시지가 회귀하지 않는지 고정하기 위한 스텁 (#320). */
  @GetMapping("/data-integrity-violation/{kind}")
  public void dataIntegrityViolationClassified(@PathVariable String kind) {
    String causeMsg =
        switch (kind) {
          case "duplicate" ->
              "ERROR: duplicate key value violates unique constraint \"uk_dataset_name\"";
          case "fk" ->
              "ERROR: insert or update on table \"dataset_column\" violates foreign key"
                  + " constraint \"fk_dataset\"";
          // 삭제 쪽 외래 키 위반 — 다른 곳에서 참조 중인 행을 지움(WD-99)
          case "fk-delete" ->
              "ERROR: update or delete on table \"api_connection\" violates foreign key"
                  + " constraint \"fk_step_conn\" on table \"pipeline_step\"";
          // 방향을 알 수 없는 외래 키 위반 문구(WD-99)
          case "fk-unknown" -> "ERROR: violates foreign key constraint \"fk_x\"";
          default ->
              "ERROR: new row for relation \"dataset\" violates check constraint"
                  + " \"ck_dataset_status\"";
        };
    throw new DataIntegrityViolationException("SQL [...]", new RuntimeException(causeMsg));
  }

  /**
   * DB 데이터 오류(SQLState 22xxx)를 담은 무결성 위반 (WD-98). 실제 jOOQ/Spring 래핑처럼 SQL 문·내부 테이블명이 바깥 메시지에, 서버
   * 구조화 오류가 원인에 실린다 — 응답이 한국어 사유 400 이고 원문이 새지 않는지 검증한다. kind=duplicate 는 23505(22xxx 아님) 대조군.
   */
  @GetMapping("/data-integrity-violation-data-error/{kind}")
  public void dataIntegrityViolationDataError(@PathVariable String kind) {
    String raw =
        switch (kind) {
          case "numeric" -> "SERROR\0C22003\0Mnumeric field overflow\0";
          case "varchar" ->
              "SERROR\0C22001\0Mvalue too long for type character varying(3)\0ccode\0";
          default ->
              "SERROR\0C23505\0Mduplicate key value violates unique constraint \"uk_secret\"\0";
        };
    throw new DataIntegrityViolationException(
        "SQL [insert into data.\"secret_tbl\" (code) values (?)]; ERROR: " + kind,
        new PSQLException(new ServerErrorMessage(raw)));
  }

  /**
   * Tibero 숫자 자릿수 초과(-5111) 를 감싼 무결성 위반 (WD-266) — Tibero 드라이버는 SQLState 대신 벤더 코드를 싣고, 원문에는 사용자 값이
   * 섞일 수 있다(여기서는 "secret").
   */
  @GetMapping("/data-integrity-violation-tibero/number-overflow")
  public void dataIntegrityViolationTiberoNumberOverflow() {
    throw new DataIntegrityViolationException(
        "Tibero; uncategorized SQLException", new SQLException("JDBC-5111: secret", null, -5111));
  }

  /**
   * PostgreSQL 구조화 오류(세부)를 실은 외래 키 위반 (WD-99) — 메시지에 방향 단서가 없고 세부(still referenced)로만 삭제 쪽임을 알 수 있는
   * 경우다.
   */
  @GetMapping("/data-integrity-violation-pg/fk-delete-detail")
  public void dataIntegrityViolationPgFkDeleteDetail() {
    throw new DataIntegrityViolationException(
        "SQL [delete from api_connection ...]; ERROR: fk",
        new PSQLException(
            new ServerErrorMessage(
                "SERROR\0C23503\0Mviolates foreign key constraint \"fk_y\"\0DKey (id)=(7) is still"
                    + " referenced from table \"pipeline_step\".\0nfk_y\0")));
  }

  @GetMapping("/account-locked")
  public void accountLocked() {
    throw new AccountLockedException("Too many failed login attempts. Please try again later.");
  }

  /**
   * 데이터셋 애드혹 쿼리 경로(#385 Task 3)의 SqlValidator 거부가 실제로 어떤 HTTP 응답으로 새는지 고정한다. UnsafeSqlException 이
   * 별도 매핑 없이 500 으로 새면 안 된다 — 이미 GlobalExceptionHandler#handleUnsafeSql 이 매핑을 갖고 있지만(파이프라인 SQL 스텝,
   * #136), 데이터셋 경로도 같은 예외 타입을 던지므로 동일 매핑을 탄다.
   */
  @GetMapping("/unsafe-sql")
  public void unsafeSql() {
    throw new com.smartfirehub.pipeline.exception.UnsafeSqlException(
        "허용되지 않는 스키마 참조: 'public.user'. data 스키마만 사용할 수 있습니다.");
  }

  /**
   * Tibero 미한정 테이블 거부(UnsafeSqlException 하위 타입, WD-81)가 하위 클래스여도 별도 매핑 없이 handleUnsafeSql 의 400 으로
   * 나가는지 고정하기 위한 스텁.
   */
  @GetMapping("/tibero-unqualified-table")
  public void tiberoUnqualifiedTable() {
    throw new TiberoUnqualifiedTableException(
        SqlValidator.tiberoUnqualifiedTableMessage("GENIA_T5", "orders"));
  }

  @GetMapping("/unexpected-error")
  public void unexpectedError() {
    throw new RuntimeException("Something unexpected happened");
  }

  @PostMapping("/method-argument-not-valid")
  public void methodArgumentNotValid(@RequestBody @Valid ValidatedBody body) {
    // validation failure is triggered by Spring before reaching here
  }

  /** 커밋 전 응답에서 난 일반 IOException — 예전엔 빈 200 이 나갔다(WD-204). */
  @GetMapping("/io-error")
  public void ioError() throws java.io.IOException {
    throw new java.io.IOException("Unsupported Excel format: UNKNOWN");
  }

  /** 클라이언트가 끊어진 경우의 IOException — 기존처럼 조용히 끝나야 한다(bfd5c1fc). */
  @GetMapping("/io-broken-pipe")
  public void ioBrokenPipe() throws java.io.IOException {
    throw new java.io.IOException("Broken pipe");
  }

  /** 파서가 파일을 읽지 못한 경우 — 한국어 안내와 400(WD-204). */
  @GetMapping("/file-unreadable")
  public void fileUnreadable() {
    throw new com.smartfirehub.dataimport.exception.FileUnreadableException(
        com.smartfirehub.dataimport.exception.FileUnreadableException.EXCEL_UNREADABLE,
        new IllegalArgumentException(
            "No valid entries or contents found, this is not a valid OOXML (Office Open XML) file"));
  }
}
