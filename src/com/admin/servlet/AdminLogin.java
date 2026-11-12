package com.admin.servlet;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import com.dao.AdminDAO;
import com.db.DBConnect;
import com.entity.User;
import com.security.LoginThrottle;

@WebServlet("/adminLogin")
public class AdminLogin extends HttpServlet {

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

		String email = req.getParameter("email");
		String password = req.getParameter("password");
		String ip = req.getRemoteAddr();

		HttpSession session = req.getSession();

		if (LoginThrottle.isBlocked("admin", email, ip)) {
			session.setAttribute("errorMsg", "Too many failed attempts, try again later");
			resp.sendRedirect("admin_login.jsp");
			return;
		}

		Connection conn = DBConnect.getConn();
		User account = null;
		try {
			account = new AdminDAO(conn).login(email, password);
		} finally {
			closeQuietly(conn);
		}

		if (account != null) {
			LoginThrottle.recordSuccess("admin", email, ip);
			// Nouvelle session a la connexion : empeche la fixation de session et
			// garantit une seule identite (un seul role) par session.
			session.invalidate();
			session = req.getSession(true);
			session.setAttribute("adminObj", account);
			resp.sendRedirect("admin/index.jsp");
		} else {
			LoginThrottle.recordFailure("admin", email, ip);
			session.setAttribute("errorMsg", "invalid email & password");
			resp.sendRedirect("admin_login.jsp");
		}

	}

	private static void closeQuietly(Connection c) {
		if (c != null) {
			try {
				c.close();
			} catch (SQLException e) {
				// rien a faire
			}
		}
	}

}
