#!/bin/sh
# Prepara a assinatura de release.
#
#   tools/dev/setup-release-signing.sh             gera a keystore (só na primeira vez)
#   tools/dev/setup-release-signing.sh --secrets   também grava os secrets no GitHub (precisa do `gh` autenticado)
#
# Cria, na raiz do projeto e fora do git:
#   papo-release.jks      a chave que assina as releases
#   keystore.properties   onde o Gradle lê a senha para builds locais
#
# GUARDE UMA CÓPIA DESSES DOIS ARQUIVOS FORA DESTA MÁQUINA. Sem a keystore não há
# como publicar uma atualização que o Android aceite por cima da versão instalada.
set -eu

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
KEYSTORE="$ROOT/papo-release.jks"
PROPERTIES="$ROOT/keystore.properties"
ALIAS=papo

property() { sed -n "s/^$1=//p" "$PROPERTIES" | head -n 1; }

if [ -f "$KEYSTORE" ] && [ -f "$PROPERTIES" ]; then
    echo "Usando a keystore existente: $KEYSTORE"
elif [ -f "$KEYSTORE" ] || [ -f "$PROPERTIES" ]; then
    echo "erro: só um de papo-release.jks e keystore.properties existe; restaure o par ou apague o que sobrou" >&2
    exit 1
else
    umask 077
    # A senha nunca aparece na tela nem na linha de comando: vai direto para o arquivo e para o keytool por variável.
    PAPO_STOREPASS=$(head -c 32 /dev/urandom | base64 | tr -d '/+=\n' | cut -c1-32)
    export PAPO_STOREPASS
    keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 \
        -storepass:env PAPO_STOREPASS -keypass:env PAPO_STOREPASS \
        -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10950 \
        -dname "CN=Papo Mobile" >/dev/null 2>&1
    {
        echo "storeFile=papo-release.jks"
        echo "storePassword=$PAPO_STOREPASS"
        echo "keyAlias=$ALIAS"
        echo "keyPassword=$PAPO_STOREPASS"
    } > "$PROPERTIES"
    echo "Keystore criada: $KEYSTORE"
fi

PAPO_STOREPASS=$(property storePassword)
export PAPO_STOREPASS
echo "Impressão digital do certificado (SHA-256):"
keytool -list -v -keystore "$KEYSTORE" -storepass:env PAPO_STOREPASS -alias "$(property keyAlias)" 2>/dev/null \
    | sed -n 's/^[[:space:]]*SHA256: /  /p'

if [ "${1:-}" = "--secrets" ]; then
    # Valores entram por stdin, não por argumento, para não ficarem na lista de processos.
    base64 -w0 "$KEYSTORE" | gh secret set RELEASE_KEYSTORE_BASE64
    printf %s "$PAPO_STOREPASS" | gh secret set RELEASE_KEYSTORE_PASSWORD
    printf %s "$(property keyAlias)" | gh secret set RELEASE_KEY_ALIAS
    printf %s "$(property keyPassword)" | gh secret set RELEASE_KEY_PASSWORD
    echo "Secrets gravados no repositório do GitHub."
else
    echo "Para gravar os secrets no GitHub: $0 --secrets"
fi
