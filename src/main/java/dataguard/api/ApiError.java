package dataguard.api;

import java.time.Instant;
import java.util.Map;

/** 稳定的错误响应；不含堆栈、SQL、数据库凭据或服务器绝对路径。 */
public record ApiError(Instant timestamp, int status, String code, String message,
                       String path, Map<String, String> details) {
}
