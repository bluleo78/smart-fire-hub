package com.smartfirehub.global.exception;

import com.smartfirehub.auth.exception.EmailAlreadyExistsException;
import com.smartfirehub.auth.exception.InvalidCredentialsException;
import com.smartfirehub.auth.exception.InvalidTokenException;
import com.smartfirehub.auth.exception.UsernameAlreadyExistsException;
import com.smartfirehub.ai.exception.AiSessionNotFoundException;
import com.smartfirehub.dataimport.exception.ConcurrentImportException;
import com.smartfirehub.dataimport.exception.ImportValidationException;
import com.smartfirehub.dataimport.exception.UnsupportedFileTypeException;
import com.smartfirehub.dataset.exception.*;
import com.smartfirehub.global.dto.ErrorResponse;
import com.smartfirehub.pipeline.exception.CyclicDependencyException;
import com.smartfirehub.pipeline.exception.CyclicTriggerDependencyException;
import com.smartfirehub.pipeline.exception.PipelineNotFoundException;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.exception.TriggerNotFoundException;
import com.smartfirehub.role.exception.RoleNotFoundException;
import com.smartfirehub.role.exception.SystemRoleModificationException;
import com.smartfirehub.user.exception.UserDeactivatedException;
import com.smartfirehub.user.exception.UserNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.put(error.getField(), error.getDefaultMessage())
        );
        ErrorResponse response = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                "Validation failed",
                fieldErrors
        );
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(UsernameAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleUsernameAlreadyExists(UsernameAlreadyExistsException ex) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                "Conflict",
                ex.getMessage(),
                null
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                "Conflict",
                ex.getMessage(),
                null
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.UNAUTHORIZED.value(),
                "Unauthorized",
                ex.getMessage(),
                null
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidTokenException ex) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.UNAUTHORIZED.value(),
                "Unauthorized",
                ex.getMessage(),
                null
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        String message = "Data integrity violation";
        if (ex.getCause() != null) {
            String causeMsg = ex.getCause().getMessage();
            if (causeMsg != null && causeMsg.contains("duplicate key")) {
                message = "Data integrity violation: duplicate entry";
            } else if (causeMsg != null && causeMsg.contains("foreign key")) {
                message = "Data integrity violation: referenced record not found";
            }
        }
        ErrorResponse response = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                "Conflict",
                message,
                null
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                "Forbidden",
                ex.getMessage(),
                null
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException ex) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                ex.getMessage(),
                null
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(RoleNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRoleNotFound(RoleNotFoundException ex) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                ex.getMessage(),
                null
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(SystemRoleModificationException.class)
    public ResponseEntity<ErrorResponse> handleSystemRoleModification(SystemRoleModificationException ex) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                ex.getMessage(),
                null
        );
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(UserDeactivatedException.class)
    public ResponseEntity<ErrorResponse> handleUserDeactivated(UserDeactivatedException ex) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.UNAUTHORIZED.value(),
                "Unauthorized",
                ex.getMessage(),
                null
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler(DatasetNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleDatasetNotFound(DatasetNotFoundException ex) {
        ErrorResponse response = new ErrorResponse(HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage(), null);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(CategoryNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCategoryNotFound(CategoryNotFoundException ex) {
        ErrorResponse response = new ErrorResponse(HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage(), null);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(DuplicateDatasetNameException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateDatasetName(DuplicateDatasetNameException ex) {
        ErrorResponse response = new ErrorResponse(HttpStatus.CONFLICT.value(), "Conflict", ex.getMessage(), null);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(InvalidTableNameException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTableName(InvalidTableNameException ex) {
        ErrorResponse response = new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "Bad Request", ex.getMessage(), null);
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(ColumnModificationException.class)
    public ResponseEntity<ErrorResponse> handleColumnModification(ColumnModificationException ex) {
        ErrorResponse response = new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "Bad Request", ex.getMessage(), null);
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(ImportValidationException.class)
    public ResponseEntity<ErrorResponse> handleImportValidation(ImportValidationException ex) {
        Map<String, String> errorMap = new HashMap<>();
        List<String> errors = ex.getErrors();
        for (int i = 0; i < errors.size(); i++) {
            errorMap.put("error_" + i, errors.get(i));
        }
        ErrorResponse response = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(), "Bad Request", ex.getMessage(), errorMap
        );
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(ConcurrentImportException.class)
    public ResponseEntity<ErrorResponse> handleConcurrentImport(ConcurrentImportException ex) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.CONFLICT.value(), "Conflict", ex.getMessage(), null
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(UnsupportedFileTypeException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedFileType(UnsupportedFileTypeException ex) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(), "Bad Request", ex.getMessage(), null
        );
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(PipelineNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePipelineNotFound(PipelineNotFoundException ex) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage(), null
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(CyclicDependencyException.class)
    public ResponseEntity<ErrorResponse> handleCyclicDependency(CyclicDependencyException ex) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(), "Bad Request", ex.getMessage(), null
        );
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(ScriptExecutionException.class)
    public ResponseEntity<ErrorResponse> handleScriptExecution(ScriptExecutionException ex) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error", ex.getMessage(), null
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    @ExceptionHandler(com.smartfirehub.dataset.exception.SqlQueryException.class)
    public ResponseEntity<ErrorResponse> handleSqlQuery(com.smartfirehub.dataset.exception.SqlQueryException ex) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(), "Bad Request", ex.getMessage(), null
        );
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(com.smartfirehub.dataset.exception.RowNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRowNotFound(com.smartfirehub.dataset.exception.RowNotFoundException ex) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage(), null
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(), "Bad Request", ex.getMessage(), null
        );
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(AiSessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAiSessionNotFound(AiSessionNotFoundException ex) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage(), null
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(TriggerNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTriggerNotFound(TriggerNotFoundException ex) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage(), null
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(CyclicTriggerDependencyException.class)
    public ResponseEntity<ErrorResponse> handleCyclicTriggerDependency(CyclicTriggerDependencyException ex) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.CONFLICT.value(), "Conflict", ex.getMessage(), null
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }
}
