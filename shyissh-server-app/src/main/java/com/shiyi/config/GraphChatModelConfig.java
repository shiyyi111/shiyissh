package com.shiyi.config;

import com.shiyi.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.shiyi.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import com.shiyi.domain.agent.service.IIntentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 图链路的模型装配：把 yml 里声明的模型配置变成一个 {@code ChatModel} bean。
 * <p>
 * {@code LlmNode} 通过 {@code ObjectProvider<ChatModel>.getIfAvailable()} 取模型，
 * 所以容器里必须有这个 bean；这里按 yml 装配它，
 * 装配不出来时返回 null、应用照常启动，请求期由 {@code LlmNode} 抛出带排查提示的异常。
 * <p>
 * 取值优先级：yml（{@code ai.agent.config.tables.*.module.ai-api} + {@code chat-model.model}）
 * → 系统属性 / 环境变量 → 默认值。
 *
 * @author shiyi
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AiAgentAutoConfigProperties.class)
public class GraphChatModelConfig {

    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode";
    private static final String DEFAULT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final String DEFAULT_MODEL = "deepseek-v4.1-flash";

    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel chatModel(AiAgentAutoConfigProperties properties, IIntentService intentService) {
        ModelSetting setting = resolve(properties);
        if (setting.apiKey == null) {
            log.warn("未找到可用的模型 api-key（yml module.ai-api.api-key / llm.api-key / DASHSCOPE_API_KEY 均为空），"
                    + "图链路调用模型时会报错，请补配置");
            return null;
        }
        log.info("图链路模型装配完成：baseUrl={} model={} completionsPath={}",
                setting.baseUrl, setting.model, setting.completionsPath);

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(setting.baseUrl)
                .apiKey(setting.apiKey)
                .completionsPath(setting.completionsPath)
                .build();

        // 意图识别复用同一套 api 与模型名：LLMIntentClassifier 会自建一个无工具回调、
        // temperature=0.1 的 ChatModel，与主模型隔离；不注入则分类器只能给出 UNKNOWN。
        intentService.configure(api, setting.model);

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(setting.model)
                        .temperature(0.0)
                        .build())
                .build();
    }

    private ModelSetting resolve(AiAgentAutoConfigProperties properties) {
        String apiKey = null;
        String baseUrl = null;
        String model = null;
        String completionsPath = null;

        Map<String, AiAgentConfigTableVO> tables = properties == null ? null : properties.getTables();
        if (tables != null) {
            for (AiAgentConfigTableVO table : tables.values()) {
                if (table == null || table.getModule() == null) {
                    continue;
                }
                AiAgentConfigTableVO.Module.AiApi aiApi = table.getModule().getAiApi();
                if (aiApi != null && notBlank(aiApi.getApiKey())) {
                    apiKey = aiApi.getApiKey();
                    baseUrl = aiApi.getBaseUrl();
                    completionsPath = aiApi.getCompletionsPath();
                }
                AiAgentConfigTableVO.Module.ChatModel chatModel = table.getModule().getChatModel();
                if (chatModel != null && notBlank(chatModel.getModel())) {
                    model = chatModel.getModel();
                }
                if (apiKey != null) {
                    break;
                }
            }
        }

        if (apiKey == null) {
            apiKey = firstNotBlank(System.getProperty("llm.api-key"),
                    System.getenv("DASHSCOPE_API_KEY"),
                    System.getenv("LLM_API_KEY"));
        }
        if (baseUrl == null) {
            baseUrl = firstNotBlank(System.getProperty("llm.base-url"), System.getenv("LLM_BASE_URL"), DEFAULT_BASE_URL);
        }
        if (completionsPath == null) {
            completionsPath = DEFAULT_COMPLETIONS_PATH;
        }
        if (model == null) {
            model = firstNotBlank(System.getProperty("llm.model"), System.getenv("LLM_MODEL"), DEFAULT_MODEL);
        }
        return new ModelSetting(apiKey, baseUrl, completionsPath, model);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstNotBlank(String... values) {
        for (String value : values) {
            if (notBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private record ModelSetting(String apiKey, String baseUrl, String completionsPath, String model) {
    }

}