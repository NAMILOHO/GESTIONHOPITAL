package com.call;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * POST /wsTicket : delivre un ticket de connexion WebSocket a l'utilisateur connecte (patient ou medecin).
 * L'identite vient de la session serveur. Refus : 401 (non connecte), 403 (administrateur : pas d'appels),
 * 409 (session ambigue avec deux roles), 429 (trop de tickets en attente).
 */
@WebServlet("/wsTicket")
public class WsTicketServlet extends HttpServlet {

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

		resp.setHeader("Cache-Control", "no-store");
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");

		// En-tete personnalise : un formulaire d'un autre site ne peut pas l'ajouter.
		if (!"XMLHttpRequest".equals(req.getHeader("X-Requested-With"))) {
			resp.sendError(HttpServletResponse.SC_FORBIDDEN);
			return;
		}

		SessionIdentity.Result who = SessionIdentity.resolve(req.getSession(false));
		if (!who.ok()) {
			resp.sendError(who.status);
			return;
		}

		String ticket = TicketStore.instance().issue(who.identity);
		if (ticket == null) {
			resp.sendError(429);
			return;
		}
		resp.getWriter().write(Json.obj("ticket", ticket, "expiresIn", 30));
	}
}
