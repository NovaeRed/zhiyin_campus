package cn.redture.campus.exception;

import cn.redture.campus.pojo.model.RestResult;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理器 捕获Controller层抛出的异常，并返回统一的JSON响应格式
 */
@Slf4j
@RestControllerAdvice
public class GlobalException {

    /**
     * 处理自定义的业务异常
     *
     * @param e BaseException
     * @return RestResult
     */
    @ExceptionHandler(BaseException.class)
    public RestResult<Void> handleBaseException(
        BaseException e, jakarta.servlet.http.HttpServletResponse response) {
        response.setStatus(e.getCode());
        log.warn(
            "业务异常: code={}, errorCode={}, message={}",
            e.getCode(),
            e.getErrorCode(),
            e.getMessage());
        return RestResult.error(HttpStatus.valueOf(e.getCode()), e.getMessage(), e.getErrorCode());
    }

    /**
     * 处理@Valid注解校验失败的异常
     *
     * @param e MethodArgumentNotValidException
     * @return RestResult
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public RestResult<String> handleValidationExceptions(MethodArgumentNotValidException e) {
        String errorMessage = e.getBindingResult().getAllErrors().getFirst().getDefaultMessage();
        log.warn("参数校验失败: {}", errorMessage);
        return RestResult.badRequest(errorMessage);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public RestResult<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("请求参数或上传内容无效: {}", e.getMessage());
        return RestResult.badRequest(e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public RestResult<Void> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过大小限制: {}", e.getMessage());
        return RestResult.badRequest("上传文件超过大小限制");
    }

    @ExceptionHandler({
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.web.multipart.support.MissingServletRequestPartException.class,
        org.springframework.validation.BindException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public RestResult<Void> handleMalformedRequest(Exception e) {
        // 解析失败时使用统一的请求格式错误消息。
        log.warn("请求格式无效: {}", e.getClass().getSimpleName());
        return RestResult.badRequest("请求格式或参数不合法，请检查请求体、参数类型及必填文件");
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public RestResult<Void> handleUnsupportedMethod(Exception e) {
        return RestResult.error(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持", "METHOD_NOT_ALLOWED");
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public RestResult<Void> handleUnsupportedMediaType(Exception e) {
        return RestResult.error(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请求内容类型不支持", "UNSUPPORTED_MEDIA_TYPE");
    }

    /**
     * 处理所有未被捕获的异常，作为最终防线
     *
     * @param e Exception
     * @return RestResult
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public RestResult<Void> handleGlobalException(Exception e) {
        log.error("未捕获的系统异常: ", e);
        return RestResult.internalError("服务器内部错误，请稍后重试");
    }
}
