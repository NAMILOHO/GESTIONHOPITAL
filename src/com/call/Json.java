package com.call;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON minimal et STRICT pour le protocole de signalisation.
 *
 * Seuls les objets "plats" sont acceptes (valeurs : texte, nombre, true/false, null). Objets et tableaux
 * imbriques, cles dupliquees, caracteres de controle et texte apres l'objet sont refuses : cela reduit la
 * surface d'attaque des messages envoyes par les clients. Les donnees structurees (candidat ICE) voyagent
 * donc sous forme de texte.
 */
public final class Json {

	private static final int MAX_KEYS = 20;

	private final String s;
	private int i;

	private Json(String s) {
		this.s = s;
	}

	/** Analyse un objet JSON plat. Leve IllegalArgumentException si le message est invalide. */
	public static Map<String, Object> parseFlat(String text) {
		if (text == null) {
			throw new IllegalArgumentException("null");
		}
		return new Json(text).parseObject();
	}

	private Map<String, Object> parseObject() {
		Map<String, Object> m = new LinkedHashMap<String, Object>();
		skipWs();
		expect('{');
		skipWs();
		if (peek() == '}') {
			i++;
		} else {
			while (true) {
				skipWs();
				String key = parseString();
				skipWs();
				expect(':');
				skipWs();
				Object value = parseValue();
				if (m.containsKey(key)) {
					throw new IllegalArgumentException("duplicate key");
				}
				if (m.size() >= MAX_KEYS) {
					throw new IllegalArgumentException("too many keys");
				}
				m.put(key, value);
				skipWs();
				char c = next();
				if (c == ',') {
					continue;
				}
				if (c == '}') {
					break;
				}
				throw new IllegalArgumentException("expected , or }");
			}
		}
		skipWs();
		if (i != s.length()) {
			throw new IllegalArgumentException("trailing data");
		}
		return m;
	}

	private Object parseValue() {
		char c = peek();
		if (c == '"') {
			return parseString();
		}
		if (c == 't') {
			literal("true");
			return Boolean.TRUE;
		}
		if (c == 'f') {
			literal("false");
			return Boolean.FALSE;
		}
		if (c == 'n') {
			literal("null");
			return null;
		}
		if (c == '-' || (c >= '0' && c <= '9')) {
			return parseNumber();
		}
		throw new IllegalArgumentException("unexpected value (nested objects/arrays not allowed)");
	}

	private Object parseNumber() {
		int start = i;
		if (peek() == '-') {
			i++;
		}
		if (peek() == '0') {
			i++;
		} else if (peek() >= '1' && peek() <= '9') {
			while (i < s.length() && Character.isDigit(s.charAt(i)) && s.charAt(i) < 128) {
				i++;
			}
		} else {
			throw new IllegalArgumentException("bad number");
		}
		boolean integer = true;
		if (i < s.length() && s.charAt(i) == '.') {
			integer = false;
			i++;
			int d = i;
			while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
				i++;
			}
			if (i == d) {
				throw new IllegalArgumentException("bad number");
			}
		}
		if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
			integer = false;
			i++;
			if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
				i++;
			}
			int d = i;
			while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
				i++;
			}
			if (i == d) {
				throw new IllegalArgumentException("bad number");
			}
		}
		String t = s.substring(start, i);
		try {
			return integer ? (Object) Long.valueOf(Long.parseLong(t)) : (Object) Double.valueOf(Double.parseDouble(t));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("bad number");
		}
	}

	private String parseString() {
		expect('"');
		StringBuilder sb = new StringBuilder();
		while (true) {
			if (i >= s.length()) {
				throw new IllegalArgumentException("unterminated string");
			}
			char c = s.charAt(i++);
			if (c == '"') {
				return sb.toString();
			}
			if (c < 0x20) {
				throw new IllegalArgumentException("control character in string");
			}
			if (c != '\\') {
				sb.append(c);
				continue;
			}
			if (i >= s.length()) {
				throw new IllegalArgumentException("bad escape");
			}
			char e = s.charAt(i++);
			switch (e) {
			case '"':
			case '\\':
			case '/':
				sb.append(e);
				break;
			case 'b':
				sb.append('\b');
				break;
			case 'f':
				sb.append('\f');
				break;
			case 'n':
				sb.append('\n');
				break;
			case 'r':
				sb.append('\r');
				break;
			case 't':
				sb.append('\t');
				break;
			case 'u':
				if (i + 4 > s.length()) {
					throw new IllegalArgumentException("bad unicode escape");
				}
				int code = 0;
				for (int k = 0; k < 4; k++) {
					int d = Character.digit(s.charAt(i + k), 16);
					if (d < 0) {
						throw new IllegalArgumentException("bad unicode escape");
					}
					code = code * 16 + d;
				}
				i += 4;
				sb.append((char) code);
				break;
			default:
				throw new IllegalArgumentException("bad escape");
			}
		}
	}

	private void literal(String word) {
		if (!s.startsWith(word, i)) {
			throw new IllegalArgumentException("bad literal");
		}
		i += word.length();
	}

	private void skipWs() {
		while (i < s.length()) {
			char c = s.charAt(i);
			if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
				i++;
			} else {
				break;
			}
		}
	}

	private char peek() {
		if (i >= s.length()) {
			throw new IllegalArgumentException("unexpected end");
		}
		return s.charAt(i);
	}

	private char next() {
		char c = peek();
		i++;
		return c;
	}

	private void expect(char c) {
		if (next() != c) {
			throw new IllegalArgumentException("expected " + c);
		}
	}

	// ------------------------------------------------------------------ ecriture

	/** Construit un objet JSON a partir de paires cle, valeur (String, Number, Boolean ou null). */
	public static String obj(Object... kv) {
		StringBuilder sb = new StringBuilder("{");
		for (int k = 0; k + 1 < kv.length; k += 2) {
			if (k > 0) {
				sb.append(',');
			}
			quote(sb, String.valueOf(kv[k]));
			sb.append(':');
			Object v = kv[k + 1];
			if (v == null) {
				sb.append("null");
			} else if (v instanceof Number || v instanceof Boolean) {
				sb.append(v.toString());
			} else {
				quote(sb, v.toString());
			}
		}
		return sb.append('}').toString();
	}

	/** Texte JSON entre guillemets, correctement echappe. */
	public static String quote(String t) {
		StringBuilder sb = new StringBuilder();
		quote(sb, t);
		return sb.toString();
	}

	private static void quote(StringBuilder sb, String t) {
		sb.append('"');
		for (int k = 0; k < t.length(); k++) {
			char c = t.charAt(k);
			switch (c) {
			case '"':
				sb.append("\\\"");
				break;
			case '\\':
				sb.append("\\\\");
				break;
			case '\b':
				sb.append("\\b");
				break;
			case '\f':
				sb.append("\\f");
				break;
			case '\n':
				sb.append("\\n");
				break;
			case '\r':
				sb.append("\\r");
				break;
			case '\t':
				sb.append("\\t");
				break;
			default:
				if (c < 0x20 || c == '\u2028' || c == '\u2029') {
					sb.append(String.format("\\u%04x", (int) c));
				} else {
					sb.append(c);
				}
			}
		}
		sb.append('"');
	}
}
