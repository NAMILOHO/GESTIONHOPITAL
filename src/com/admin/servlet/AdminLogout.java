package com.admin.servlet;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

@WebServlet("/adminLogout")
public class AdminLogout extends HttpServlet {
	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

		// Fin de session complete (et non plus simple retrait d'un attribut), puis nouvelle session pour le message.
		req.getSession().invalidate();
		HttpSession session = req.getSession(true);
		session.setAttribute("succMsg", "Admin Logout Sucessfully");
		resp.sendRedirect("admin_login.jsp");

	}

}
