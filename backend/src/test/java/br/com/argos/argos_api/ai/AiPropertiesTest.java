package br.com.argos.argos_api.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiPropertiesTest {

    @Test
    void onlyRoutesWithAKeyJoinTheChain() {
        AiProperties properties = new AiProperties();
        properties.setRoutes(List.of(route("openai/gpt-oss-120b", "gsk_x"), route("gemini-flash-lite-latest", ""),
                route("openai/gpt-oss-20b", "gsk_x")));

        assertThat(properties.activeRoutes()).extracting(AiProperties.Route::getModel)
                .containsExactly("openai/gpt-oss-120b", "openai/gpt-oss-20b");
        assertThat(properties.llmConfigured()).isTrue();
        assertThat(properties.activeRoutes()).allMatch(AiProperties.Route::free);
    }

    @Test
    void withoutKeysOrWithTheLlmSwitchedOffNoModelIsConfigured() {
        AiProperties noKeys = new AiProperties();
        noKeys.setRoutes(List.of(route("openai/gpt-oss-120b", "")));
        AiProperties switchedOff = new AiProperties();
        switchedOff.setRoutes(List.of(route("openai/gpt-oss-120b", "gsk_x")));
        switchedOff.setLlmEnabled(false);

        assertThat(noKeys.llmConfigured()).isFalse();
        assertThat(switchedOff.llmConfigured()).isFalse();
    }

    private static AiProperties.Route route(String model, String key) {
        AiProperties.Route route = new AiProperties.Route();
        route.setModel(model);
        route.setApiKey(key);
        return route;
    }
}
