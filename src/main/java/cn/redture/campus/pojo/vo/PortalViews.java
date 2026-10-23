package cn.redture.campus.pojo.vo;

import lombok.Data;
import java.time.OffsetDateTime;
import java.util.List;

public final class PortalViews {
    private PortalViews() {}
    public record Page<T>(List<T> items, long total, int page, int pageSize) {}
    public record Account(Long id, String studentNo, String username, String role, boolean canManageDocuments) {}
    @Data public static class Document {
        private Long id;
        private String title;
        private String fileType;
        private String version;
        private String status;
        private String metadata;
        private String chunkStrategy;
        private String createdByName;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
        private long parentChunks;
        private long childChunks;
    }
    @Data public static class Chunk {
        private Long id;
        private Long parentId;
        private String chunkLevel;
        private String content;
        private String metadata;
        private String targetMajor;
        private Integer targetGrade;
        private String docStatus;
    }
    @Data public static class Session {
        private Long id;
        private String title;
        private long queryCount;
        private OffsetDateTime updatedAt;
    }
    @Data public static class HistoryQuery {
        private Long queryId;
        private String question;
        private String state;
        private String answer;
        private String sourcesJson;
        private OffsetDateTime createdAt;
    }
}
