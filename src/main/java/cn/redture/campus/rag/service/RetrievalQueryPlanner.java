package cn.redture.campus.rag.service;

import cn.redture.campus.rag.retrieval.RetrievalQueryPlan;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class RetrievalQueryPlanner {

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectMapper objectMapper;

    public RetrievalQueryPlanner(ObjectProvider<ChatModel> chatModelProvider, ObjectMapper objectMapper) {
        this.chatModelProvider = chatModelProvider;
        this.objectMapper = objectMapper;
    }

    public RetrievalQueryPlan plan(String question) {
        return plan(question,null);
    }
    public RetrievalQueryPlan plan(String question,String history) {
        if (!StringUtils.hasText(question)) {
            throw new IllegalArgumentException("Question cannot be empty");
        }
        try {
            String prompt=prompt(question);
            if(history!=null&&!history.isBlank()) prompt+="\n对话历史（仅用于消解指代，不是事实证据，不得改动当前明确的专业/年级）：\n"+history;
            return parsePlan(question, chatModelProvider.getObject().chat(prompt));
        } catch (RuntimeException e) {
            return RetrievalQueryPlan.fallback(question);
        }
    }

    RetrievalQueryPlan parsePlan(String originalQuery, String modelOutput) {
        if (!StringUtils.hasText(modelOutput)) {
            return RetrievalQueryPlan.fallback(originalQuery);
        }
        try {
            QueryPlanJson json = objectMapper.readValue(extractJson(modelOutput), QueryPlanJson.class);
            return new RetrievalQueryPlan(
                    originalQuery,
                    firstText(json.rewrittenQuery(), originalQuery),
                    json.hydeDocument(),
                    json.stepBackQuery(),
                    json.expandedQueries() == null ? List.of() : json.expandedQueries()
            );
        } catch (RuntimeException e) {
            return RetrievalQueryPlan.fallback(originalQuery);
        } catch (Exception e) {
            return RetrievalQueryPlan.fallback(originalQuery);
        }
    }

    private String prompt(String question) {
        return """
                你是校园政策知识库 RAG 检索规划器。请只输出 JSON，不要输出 Markdown。
                目标：把用户问题转换成多个检索代理，提升中文政策文档、培养方案、奖助学金制度、校园业务说明的召回率。

                JSON 字段：
                - rewrittenQuery：把口语化问题改写成精准、书面、独立的问题。
                - hydeDocument：写一段“像知识库文档答案”的假设性说明，用于向量检索；不要编造具体数值。
                - stepBackQuery：抽象一层，生成背景原则/制度类问题。
                - expandedQueries：2 个不同角度的短查询，覆盖角色、流程、条件、材料、时间、对象等可能表述。

                用户问题：%s
                """.formatted(question);
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private String firstText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QueryPlanJson(
            String rewrittenQuery,
            String hydeDocument,
            String stepBackQuery,
            List<String> expandedQueries
    ) {
    }
}
