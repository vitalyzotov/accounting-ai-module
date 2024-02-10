package ru.vzotov.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import gigachat.v1.ChatServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.endpoint.DefaultClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestTemplate;
import ru.vzotov.ai.domain.ChatModel;
import ru.vzotov.ai.domain.EmbeddingsModel;
import ru.vzotov.ai.gigachat.GigaChatAccessTokenResponseConverter;
import ru.vzotov.ai.gigachat.GigachatEmbeddings;
import ru.vzotov.ai.gigachat.GigachatModel;
import ru.vzotov.ai.interfaces.facade.AIFacade;
import ru.vzotov.ai.interfaces.facade.impl.AIFacadeImpl;
import ru.vzotov.ai.util.BearerToken;
import ru.vzotov.cashreceipt.domain.model.PurchaseCategoryRepository;
import ru.vzotov.purchases.domain.model.PurchaseRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_PROTOTYPE;

@Configuration
@EnableConfigurationProperties(MilvusConfigProperties.class)
public class AIModule {

    private static final Logger log = LoggerFactory.getLogger(AIModule.class);

    @Bean
    AIFacade facade(
            PurchaseCategoryRepository purchaseCategoryRepository,
            PurchaseRepository purchaseRepository,
            @Value("${accounting.ai.purchases.collection}")
            String collectionName,
            MilvusServiceClient milvusClient,
            EmbeddingsModel embeddingsModel,
            ChatModel chatModel,
            ObjectMapper objectMapper) {
        return new AIFacadeImpl(purchaseCategoryRepository, purchaseRepository, collectionName, milvusClient, embeddingsModel, chatModel, objectMapper);
    }

    @Bean
    ChatModel chatModel(ChatServiceGrpc.ChatServiceBlockingStub gigachat, @Value("${accounting.ai.gigachat.model:GigaChat}") String modelName) {
        return new GigachatModel(gigachat, modelName);
    }

    @Bean
    ConnectParam milvusConnectionParameters(MilvusConfigProperties milvusConfig) {
        return ConnectParam.newBuilder()
                .withHost(milvusConfig.host())
                .withPort(milvusConfig.port())
                .build();
    }

    @Bean
    MilvusServiceClient milvusServiceClient(ConnectParam milvusConnectionParameters) {
        return new MilvusServiceClient(milvusConnectionParameters);
    }

    @Bean
    ChatServiceGrpc.ChatServiceBlockingStub gigachat(ObjectFactory<OAuth2AccessToken> accessTokenFactory) {
        AtomicReference<OAuth2AccessToken> tokenValue = new AtomicReference<>();
        ManagedChannel channel = ManagedChannelBuilder.forTarget("gigachat.devices.sberbank.ru").build();
        return ChatServiceGrpc.newBlockingStub(channel)
                .withCallCredentials(new BearerToken(() -> {
                    OAuth2AccessToken token =
                            tokenValue.updateAndGet(t ->
                                    t != null && t.getExpiresAt() != null && Instant.now().isBefore(t.getExpiresAt()) ?
                                            t : accessTokenFactory.getObject());
                    return token.getTokenValue();
                }));
    }

    @Bean
    EmbeddingsModel embeddingsClient(
            ObjectFactory<OAuth2AccessToken> accessTokenFactory,
            RestTemplateBuilder builder
    ) {
        AtomicReference<OAuth2AccessToken> tokenValue = new AtomicReference<>();

        RestTemplate restTemplate = builder
                .additionalInterceptors((request, body, execution) -> {
                    OAuth2AccessToken token =
                            tokenValue.updateAndGet(t ->
                                    t != null && t.getExpiresAt() != null && Instant.now().isBefore(t.getExpiresAt()) ?
                                            t : accessTokenFactory.getObject());
                    Instant expiresAt = token.getExpiresAt();

                    HttpHeaders headers = request.getHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
                    headers.add(HttpHeaders.AUTHORIZATION, "Bearer %s".formatted(token.getTokenValue()));
                    log.debug("Access token expires at {}. Body: {}", expiresAt, new String(body, StandardCharsets.UTF_8));
                    return execution.execute(request, body);
                })
                .build();

        //restTemplate.getInterceptors().add();
        return new GigachatEmbeddings(restTemplate);
    }

    @Bean
    @Scope(SCOPE_PROTOTYPE)
    public OAuth2AccessToken gigachatAccessToken(OAuth2AuthorizedClientManager authorizedClientManager) {
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest.withClientRegistrationId("gigachat")
                .principal("accounting")
                .build();
        OAuth2AuthorizedClient authorizedClient =
                Objects.requireNonNull(authorizedClientManager.authorize(authorizeRequest));
        return authorizedClient.getAccessToken();
    }

    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService) {
        OAuth2AuthorizedClientProvider authorizedClientProvider =
                OAuth2AuthorizedClientProviderBuilder.builder()
                        .clientCredentials(builder -> builder.accessTokenResponseClient(clientCredentialsTokenResponseClient()))
                        .build();

        AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrationRepository, authorizedClientService);
        authorizedClientManager.setAuthorizedClientProvider(authorizedClientProvider);

        return authorizedClientManager;
    }

    @Bean
    public OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> clientCredentialsTokenResponseClient() {
        DefaultClientCredentialsTokenResponseClient clientCredentialsTokenResponseClient =
                new DefaultClientCredentialsTokenResponseClient();

        OAuth2AccessTokenResponseHttpMessageConverter tokenResponseHttpMessageConverter =
                new OAuth2AccessTokenResponseHttpMessageConverter();
        tokenResponseHttpMessageConverter.setAccessTokenResponseConverter(new GigaChatAccessTokenResponseConverter());
        RestTemplate restTemplate = new RestTemplate(Arrays.asList(
                new FormHttpMessageConverter(), tokenResponseHttpMessageConverter));
        restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        restTemplate.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().add("RqUID", UUID.randomUUID().toString());
            return execution.execute(request, body);
        });

        clientCredentialsTokenResponseClient.setRestOperations(restTemplate);
        return clientCredentialsTokenResponseClient;
    }
}
