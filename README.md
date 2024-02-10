# AI module for Accounting server

## Getting started

For development environment: Install russian trusted certificates. This is necessary to communicate with Gigachat servers.

```
keytool -importcert -storepass changeit -noprompt -alias rus_root_ca -cacerts -trustcacerts -file russian_trusted/russian_trusted_root_ca_pem.crt
keytool -importcert -storepass changeit -noprompt -alias rus_sub_ca -cacerts -trustcacerts -file russian_trusted/russian_trusted_root_ca_pem.crt
```

Set environment variables

```
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GIGACHAT_CLIENT_ID=<client id>
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GIGACHAT_CLIENT_SECRET=<client secret>
```