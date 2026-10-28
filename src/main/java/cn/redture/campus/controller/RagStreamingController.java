package cn.redture.campus.controller;

import cn.redture.campus.exception.BaseException;
import cn.redture.campus.pojo.dto.RagAskRequestDTO;
import cn.redture.campus.rag.service.ReliableRagService;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.*;

@RestController
public class RagStreamingController {
    private final ReliableRagService rag;
    private final Semaphore slots = new Semaphore(16);

    public RagStreamingController(ReliableRagService rag) {
        this.rag = rag;
    }

    /**
     * 校验流式请求并占用连接槽，在虚拟线程中恢复认证上下文，推送草稿、草稿重置及校验后答案；关闭连接时尽力取消执行。
     *
     * @param request 包含问题、会话与范围的流式问答请求
     * @param key     可选幂等请求头，须与请求体中的requestId一致
     * @return 用于持续发送事件的SSE连接
     */
    @PostMapping(value = "/rag/ask/stream", produces = "text/event-stream")
    public SseEmitter stream(
        @RequestBody RagAskRequestDTO request,
        @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        if (key != null) {
            if (request.getRequestId() != null && !key.equals(request.getRequestId())) {
                throw new IllegalArgumentException("Idempotency-Key与requestId不一致");
            }
            request.setRequestId(key);
        }
        if (!slots.tryAcquire()) {
            throw new BaseException(HttpStatus.TOO_MANY_REQUESTS, "流式请求过多，请稍后重试", "STREAM_LIMIT");
        }
        if (request.getRequestId() == null) {
            request.setRequestId(UUID.randomUUID().toString());
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var emitter = new SseEmitter(180000L);
        var closed = new AtomicBoolean();
        var worker = new AtomicReference<Thread>();
        Runnable cancel =
            () -> {
                if (closed.compareAndSet(false, true)) {
                    var thread = worker.get();
                    if (thread != null) {
                        thread.interrupt();
                    }
                    try {
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        rag.cancel(request.getRequestId());
                    } catch (RuntimeException ignored) {
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }
            };
        emitter.onTimeout(cancel);
        emitter.onError(error -> cancel.run());
        emitter.onCompletion(
            () -> {
                if (!closed.get()) {
                    cancel.run();
                }
            });
        worker.set(
            Thread.ofVirtual()
                .unstarted(
                    () -> {
                        SecurityContextHolder.getContext()
                            .setAuthentication(authentication);
                        try {
                            send(
                                emitter,
                                "accepted",
                                Map.of("requestId", request.getRequestId()));
                            request.setTokenConsumer(
                                text -> {
                                    if (!closed.get()) {
                                        send(
                                            emitter,
                                            text == null
                                                ? "draft_reset"
                                                : "draft_token",
                                            text == null ? "" : text);
                                    }
                                });
                            var response = rag.ask(request);
                            if (!closed.get()) {
                                send(emitter, "answer", response);
                            }
                            closed.set(true);
                            emitter.complete();
                        } catch (RuntimeException failure) {
                            if (!closed.get()) {
                                try {
                                    send(
                                        emitter,
                                        "error",
                                        Map.of(
                                            "code",
                                            failure instanceof BaseException b
                                                ? b.getErrorCode()
                                                : "RAG_EXECUTION_FAILED",
                                            "message",
                                            failure instanceof BaseException
                                                ? failure.getMessage()
                                                : "问答执行失败，请查询请求状态"));
                                } catch (RuntimeException ignored) {
                                }
                            }
                            closed.set(true);
                            emitter.complete();
                        } finally {
                            SecurityContextHolder.clearContext();
                            slots.release();
                        }
                    }));
        worker.get().start();
        return emitter;
    }

    private static void send(SseEmitter emitter, String name, Object value) {
        try {
            emitter.send(SseEmitter.event().name(name).data(value));
        } catch (Exception failure) {
            throw new IllegalStateException("SSE connection closed", failure);
        }
    }
}
