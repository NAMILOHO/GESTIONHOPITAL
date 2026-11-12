package com.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import com.entity.User;
import com.security.PasswordUtil;

public class AdminDAO {

	// Hash factice : quand l'email est inconnu, on fait quand meme une verification de duree comparable
	// (evite de reveler par le temps de reponse si un compte admin existe).
	private static final String DUMMY_HASH = PasswordUtil.hash("dummy-password-never-used");

	private Connection conn;

	public AdminDAO(Connection conn) {
		super();
		this.conn = conn;
	}

	/** Renvoie l'administrateur si email et mot de passe sont corrects, sinon null. */
	public User login(String email, String password) {
		User admin = null;
		boolean found = false;

		try {
			String sql = "select * from admin_dtls where email=?";
			PreparedStatement ps = conn.prepareStatement(sql);
			ps.setString(1, email);

			ResultSet rs = ps.executeQuery();
			while (rs.next()) {
				found = true;
				if (PasswordUtil.verify(password, rs.getString(4))) {
					admin = new User();
					admin.setId(rs.getInt(1));
					admin.setFullName(rs.getString(2));
					admin.setEmail(rs.getString(3));
					admin.setPassword("");
					break;
				}
			}

			if (!found) {
				PasswordUtil.verify(password, DUMMY_HASH);
			}

		} catch (Exception e) {
			e.printStackTrace();
		}

		return admin;
	}

}
