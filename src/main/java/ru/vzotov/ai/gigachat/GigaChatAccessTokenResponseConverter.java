package ru.vzotov.ai.gigachat;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class GigaChatAccessTokenResponseConverter implements Converter<Map<String, Object>, OAuth2AccessTokenResponse> {
    public static final String ACCESS_TOKEN = "access_token";
    public static final String EXPIRES_AT = "expires_at";

    private static final Set<String> TOKEN_RESPONSE_PARAMETER_NAMES = Set.of(ACCESS_TOKEN, EXPIRES_AT);

    @Override
    public OAuth2AccessTokenResponse convert(Map<String, Object> source) {
        String accessToken = getParameterValue(source, ACCESS_TOKEN);
        Instant expiresAt = Instant.ofEpochMilli(getExpiresAt(source));
        Map<String, Object> additionalParameters = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (!TOKEN_RESPONSE_PARAMETER_NAMES.contains(entry.getKey())) {
                additionalParameters.put(entry.getKey(), entry.getValue());
            }
        }
        return OAuth2AccessTokenResponse.withToken(accessToken)
                .tokenType(OAuth2AccessToken.TokenType.BEARER)
                .additionalParameters(additionalParameters)
                .expiresIn(ChronoUnit.MILLIS.between(Instant.now(), expiresAt))
                .build();
    }

    private static long getExpiresAt(Map<String, Object> tokenResponseParameters) {
        return getParameterValue(tokenResponseParameters, EXPIRES_AT, 0L);
    }

    private static String getParameterValue(Map<String, Object> tokenResponseParameters, String parameterName) {
        Object obj = tokenResponseParameters.get(parameterName);
        return (obj != null) ? obj.toString() : null;
    }

    private static long getParameterValue(Map<String, Object> tokenResponseParameters, String parameterName,
                                          long defaultValue) {
        long parameterValue = defaultValue;

        Object obj = tokenResponseParameters.get(parameterName);
        if (obj != null) {
            // Final classes Long and Integer do not need to be coerced
            if (obj.getClass() == Long.class) {
                parameterValue = (Long) obj;
            } else if (obj.getClass() == Integer.class) {
                parameterValue = (Integer) obj;
            } else {
                // Attempt to coerce to a long (typically from a String)
                try {
                    parameterValue = Long.parseLong(obj.toString());
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return parameterValue;
    }

}
