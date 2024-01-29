Install russian trusted certificates

```
keytool -import -storepass changeit -noprompt -alias RUS_ROOT_CA -cacerts -trustcacerts -file russian_trusted\russian_trusted_root_ca_pem.crt
keytool -import -storepass changeit -noprompt -alias RUS_SUB_CA -cacerts -trustcacerts -file russian_trusted\russian_trusted_root_ca_pem.crt
```

Set environment variables

```
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GIGACHAT_CLIENT_ID=<client id>
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GIGACHAT_CLIENT_SECRET=<client secret>
```