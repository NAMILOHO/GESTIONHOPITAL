# Serveur TURN (coturn) pour les appels

Sans serveur TURN, les appels fonctionnent entre deux machines qui se voient directement (meme reseau, ou NAT
simples) grace au STUN. Derriere les NAT stricts des reseaux mobiles (tres frequents), il faut un relais TURN.
Le TURN ne dechiffre rien : il relaie des paquets deja chiffres (DTLS-SRTP).

## Installation (Ubuntu / Debian)

    sudo apt install coturn
    sudo sed -i 's/^#TURNSERVER_ENABLED=1/TURNSERVER_ENABLED=1/' /etc/default/coturn

## /etc/turnserver.conf (remplacer SECRET, le domaine et les certificats)

    listening-port=3478
    tls-listening-port=5349
    min-port=49152
    max-port=65535
    fingerprint
    use-auth-secret
    static-auth-secret=SECRET
    realm=turn.votre-domaine.ci
    cert=/etc/letsencrypt/live/turn.votre-domaine.ci/fullchain.pem
    pkey=/etc/letsencrypt/live/turn.votre-domaine.ci/privkey.pem
    total-quota=100
    no-multicast-peers
    no-cli
    # empeche le relais vers le reseau interne du serveur
    denied-peer-ip=10.0.0.0-10.255.255.255
    denied-peer-ip=172.16.0.0-172.31.255.255
    denied-peer-ip=192.168.0.0-192.168.255.255
    denied-peer-ip=127.0.0.0-127.255.255.255

SECRET : une longue valeur aleatoire (par exemple `openssl rand -hex 32`), identique cote Tomcat. Jamais dans le code.

Pare-feu : 3478 (UDP et TCP), 5349 (TCP), 49152-65535 (UDP).

## Cote application (Tomcat) : proprietes JVM ou variables d'environnement

    -Dturn.urls=turn:turn.votre-domaine.ci:3478?transport=udp,turns:turn.votre-domaine.ci:5349?transport=tcp
    -Dturn.secret=SECRET
    -Dturn.ttl.sec=3600            (optionnel)
    -Dice.stun.urls=stun:turn.votre-domaine.ci:3478   (optionnel, defaut : STUN public de Google)

Equivalents en variables d'environnement : TURN_URLS, TURN_SECRET, TURN_TTL_SEC, ICE_STUN_URLS.
Dans Eclipse : double-clic sur le serveur Tomcat > "Open launch configuration" > onglet Arguments > VM arguments.

Les identifiants TURN envoyes au navigateur sont temporaires (1 h par defaut) : nom "<expiration>:<role>-<id>",
mot de passe = HMAC-SHA1 du nom avec le secret. Le secret ne quitte jamais le serveur.
