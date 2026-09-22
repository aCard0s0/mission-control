package io.hermes.missioncontrol.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.hermes.missioncontrol.credentials.CredentialService;
import io.hermes.missioncontrol.errors.ApiExceptionHandler;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The model-picker endpoints. The live variant takes an API key, so it is also the one
 *  place a provider secret crosses this layer. */
class ModelCatalogControllerTest {

  private ModelCatalogService catalog;
  private CredentialService credentials;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    catalog = mock(ModelCatalogService.class);
    credentials = mock(CredentialService.class);
    mvc = MockMvcBuilders
        .standaloneSetup(new ModelCatalogController(catalog, credentials))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @Test
  void theConfiguredCatalogNeedsNoApiKey() throws Exception {
    when(catalog.configured("anthropic"))
        .thenReturn(new ModelCatalogDto("anthropic", List.of("claude-opus-5"), "config"));

    mvc.perform(get("/api/models/anthropic"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("config"))
        .andExpect(jsonPath("$.models[0]").value("claude-opus-5"));
  }

  @Test
  void aBlankApiKeyIsRejectedBeforeAnyOutboundCall() throws Exception {
    mvc.perform(post("/api/models/anthropic")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"apiKey\":\"   \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());

    mvc.perform(post("/api/models/anthropic")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest());

    // a blank key would reach the provider as an unauthenticated request and burn a
    // round trip to learn what validation already knows
    verifyNoInteractions(catalog);
  }

  @Test
  void theApiKeyIsTrimmedBeforeReachingTheService() throws Exception {
    when(catalog.live(anyString(), anyString()))
        .thenReturn(new ModelCatalogDto("anthropic", List.of("claude-opus-5"), "live"));

    mvc.perform(post("/api/models/anthropic")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"apiKey\":\"  sk-ant-abcd  \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("live"));

    // a pasted key almost always carries whitespace; sending it verbatim is a 401
    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    verify(catalog).live(eq("anthropic"), key.capture());
    assertEquals("sk-ant-abcd", key.getValue());
  }

  @Test
  void aSavedCredentialIsResolvedUnderTheProvidersOwnVariable() throws Exception {
    // the dialog that picked a credential never holds the key, so it sends the id and the
    // server reads the one variable this provider takes — not a key the client names
    when(credentials.valueFor("cred-1", "OPENAI_API_KEY")).thenReturn("sk-from-store");
    when(catalog.live(anyString(), anyString()))
        .thenReturn(new ModelCatalogDto("openai-api", List.of("gpt-6"), "live"));

    mvc.perform(post("/api/models/openai-api")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credentialId\":\"cred-1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.models[0]").value("gpt-6"));

    verify(catalog).live("openai-api", "sk-from-store");
  }

  @Test
  void aCredentialForAProviderThatTakesNoKeyIsABadRequest() throws Exception {
    mvc.perform(post("/api/models/nous")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credentialId\":\"cred-1\"}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(catalog, credentials);
  }

  @Test
  void anUnknownProviderIsANotFound() throws Exception {
    when(catalog.configured("mystery")).thenThrow(new NoSuchElementException("unknown provider: mystery"));

    mvc.perform(get("/api/models/mystery"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("unknown provider: mystery"));
  }

  @Test
  void theProviderPathVariableIsForwardedVerbatimToBothEndpoints() throws Exception {
    when(catalog.configured(anyString()))
        .thenReturn(new ModelCatalogDto("openrouter", List.of(), "config"));
    when(catalog.live(anyString(), anyString()))
        .thenReturn(new ModelCatalogDto("openrouter", List.of(), "live"));

    mvc.perform(get("/api/models/openrouter")).andExpect(status().isOk());
    mvc.perform(post("/api/models/openrouter")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"apiKey\":\"sk-or-abcd\"}"))
        .andExpect(status().isOk());

    // the service resolves provider -> curated CSV / outbound endpoint by this exact
    // string, so normalisation belongs there and must not be second-guessed here
    verify(catalog).configured("openrouter");
    verify(catalog).live("openrouter", "sk-or-abcd");
  }
}
