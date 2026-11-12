package com.call;

import javax.websocket.server.ServerEndpointConfig;

/**
 * Controle de l'en-tete Origin du handshake. Si la propriete JVM ws.allowed.origins (ou la variable
 * d'environnement WS_ALLOWED_ORIGINS) est definie, par exemple "https://hopital.example.ci", seules ces
 * origines (separees par des virgules) sont acceptees. Sinon tout est accepte : le ticket a usage unique
 * reste la protection principale contre le detournement de WebSocket depuis un autre site.
 */
public class OriginConfigurator extends ServerEndpointConfig.Configurator {

	@Override
	public boolean checkOrigin(String originHeaderValue) {
		String allowed = System.getProperty("ws.allowed.origins");
		if (allowed == null || allowed.length() == 0) {
			allowed = System.getenv("WS_ALLOWED_ORIGINS");
		}
		if (allowed == null || allowed.trim().length() == 0) {
			return true;
		}
		if (originHeaderValue == null) {
			return false;
		}
		for (String o : allowed.split(",")) {
			if (o.trim().equalsIgnoreCase(originHeaderValue.trim())) {
				return true;
			}
		}
		return false;
	}
}
