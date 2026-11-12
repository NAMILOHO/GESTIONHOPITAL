package com.call;

import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

/**
 * Point d'entree WebSocket de la signalisation : wss://hote/GESTIONHOPITAL/ws/call?ticket=...
 * Toute la logique est dans CallHub ; cette classe ne fait que relayer les evenements.
 */
@ServerEndpoint(value = "/ws/call", configurator = OriginConfigurator.class)
public class CallEndpoint {

	@OnOpen
	public void onOpen(Session session) {
		java.util.List<String> t = session.getRequestParameterMap().get("ticket");
		String ticket = (t != null && t.size() == 1) ? t.get(0) : null;
		CallHub.instance().open(session, ticket);
	}

	@OnMessage(maxMessageSize = CallHub.MAX_TEXT)
	public void onMessage(Session session, String message) {
		CallHub.instance().message(session, message);
	}

	@OnClose
	public void onClose(Session session, CloseReason reason) {
		CallHub.instance().closed(session);
	}

	@OnError
	public void onError(Session session, Throwable error) {
		// Pas de details (ils pourraient contenir des donnees de l'utilisateur) : on ferme proprement.
		CallHub.instance().failed(session);
	}
}
