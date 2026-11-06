package dataguard.api;

import dataguard.core.InspectionException;
import dataguard.service.ServiceException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ServiceException.class)
    ResponseEntity<ApiError> service(ServiceException failure, HttpServletRequest request) {
        return error(failure.getStatus(), failure.getCode(), failure.getMessage(), request, Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException failure, HttpServletRequest request) {
        Map<String, String> details = new LinkedHashMap<>();
        failure.getBindingResult().getFieldErrors().forEach(issue ->
                details.putIfAbsent(issue.getField(), issue.getDefaultMessage()));
        return error(400, "VALIDATION_ERROR", "请求字段不符合要求。", request, details);
    }

    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
            ServletRequestBindingException.class, MissingServletRequestPartException.class})
    ResponseEntity<ApiError> invalidRequest(Exception failure, HttpServletRequest request) {
        return error(400, "INVALID_REQUEST", "请求格式或参数不正确。", request, Map.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> oversized(MaxUploadSizeExceededException failure, HttpServletRequest request) {
        return error(413, "FILE_TOO_LARGE", "CSV 文件不能超过 5 MiB。", request, Map.of());
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ApiError> multipart(MultipartException failure, HttpServletRequest request) {
        return error(400, "INVALID_UPLOAD", "上传内容不是有效的 multipart 文件。", request, Map.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException failure, HttpServletRequest request) {
        return error(415, "UNSUPPORTED_MEDIA_TYPE", "请使用接口要求的 Content-Type。", request, Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException failure, HttpServletRequest request) {
        return error(405, "METHOD_NOT_ALLOWED", "该接口不支持此 HTTP 方法。", request, Map.of());
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ApiError> accept(HttpMediaTypeNotAcceptableException failure, HttpServletRequest request) {
        return error(406, "NOT_ACCEPTABLE", "该接口不支持请求的响应格式。", request, Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> missing(NoResourceFoundException failure, HttpServletRequest request) {
        return error(404, "NOT_FOUND", "请求的资源不存在。", request, Map.of());
    }

    @ExceptionHandler(InspectionException.class)
    ResponseEntity<ApiError> inspection(InspectionException failure, HttpServletRequest request) {
        Map<String, String> details = failure.getLineNumber() == null ? Map.of()
                : Map.of("lineNumber", failure.getLineNumber().toString());
        return error(400, failure.getCode(), "CSV 文件无法解析。", request, details);
    }

    @ExceptionHandler(IOException.class)
    ResponseEntity<ApiError> io(IOException failure, HttpServletRequest request) {
        return error(400, "FILE_READ_ERROR", "文件无法读取。", request, Map.of());
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> database(DataAccessException failure, HttpServletRequest request) {
        LOG.error("Database operation failed", failure);
        return error(503, "DATABASE_UNAVAILABLE", "数据存储暂时不可用。", request, Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception failure, HttpServletRequest request) {
        LOG.error("Unexpected request failure", failure);
        return error(500, "INTERNAL_ERROR", "服务未能完成请求，请查看服务日志。", request, Map.of());
    }

    private ResponseEntity<ApiError> error(int status, String code, String message,
                                            HttpServletRequest request, Map<String, String> details) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(new ApiError(Instant.now(), status, code, message,
                request.getRequestURI(), details));
    }
}
