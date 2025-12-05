package de.in.yajda.dll;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal C header parser for function prototypes. Heuristic implementation: strips comments, finds simple function prototypes like: int
 * Add(int a, float b); void DoSomething(void);
 *
 * Limitations: - Does not parse complex declarations (function pointers, macros, C++ overloads). - Attempts to extract a return type and a
 * list of parameter types (best-effort).
 *
 * Note: reading header files may fail if encoding is not UTF-8. We attempt several encodings.
 */
public class HeaderParser {

	public static class HeaderInfo {
		public final String returnType;
		public final List<String> paramTypes;

		public HeaderInfo(String returnType, List<String> paramTypes) {
			this.returnType = returnType;
			this.paramTypes = paramTypes;
		}
	}

	private static final Pattern PROTOTYPE = Pattern.compile(
			// return type (group 1), function name (group 2), parameter list (group 3)
			"([A-Za-z_\\*\\s0-9]+?)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^;\\)]*)\\)\\s*;", Pattern.MULTILINE);

	/**
	 * Parse a header file and return a map of function name -> HeaderInfo
	 */
	public Map<String, HeaderInfo> parseHeader(File headerFile) throws IOException {
		String src = readFileWithFallBackEncodings(headerFile);
		src = removeComments(src);
		Map<String, HeaderInfo> map = new LinkedHashMap<>();

		Matcher m = PROTOTYPE.matcher(src);
		while (m.find()) {
			String rawRet = m.group(1).trim();
			String name = m.group(2).trim();
			String params = m.group(3).trim();

			String ret = normalizeType(rawRet);
			List<String> paramTypes = parseParamList(params);
			map.put(name, new HeaderInfo(ret, paramTypes));
		}
		return map;
	}

	private String readFileWithFallBackEncodings(File file) throws IOException {
		// Try common encodings: UTF-8 (default), Windows-1252, ISO-8859-1, then system default.
		List<Charset> attempts = Arrays.asList(StandardCharsets.UTF_8, Charset.forName("windows-1252"), StandardCharsets.ISO_8859_1, Charset.defaultCharset());
		IOException lastEx = null;
		for (Charset cs : attempts) {
			try {
				byte[] bytes = Files.readAllBytes(file.toPath());
				return new String(bytes, cs);
			} catch (MalformedInputException mie) {
				lastEx = mie;
				// try next encoding
			} catch (IOException ioe) {
				// For other IO issues, record and try next (though unlikely)
				lastEx = ioe;
			}
		}
		// If all attempts failed, throw the last exception or generic IOException
		if (lastEx != null)
			throw lastEx;
		// Fallback (shouldn't reach here)
		return Files.readString(file.toPath(), StandardCharsets.UTF_8);
	}

	private String removeComments(String s) {
		// remove block comments /* ... */ and line comments //
		s = s.replaceAll("(?s)/\\*.*?\\*/", " ");
		s = s.replaceAll("//.*(?=[\\n\\r])", " ");
		return s;
	}

	private List<String> parseParamList(String params) {
		List<String> res = new ArrayList<>();
		if (params.isEmpty() || params.equalsIgnoreCase("void")) {
			return res;
		}
		String[] parts = params.split(",");
		for (String p : parts) {
			String t = p.trim();
			// remove any parameter name at end: naive approach -> drop last token if it looks like an identifier
			// e.g. "const char *name" -> keep "const char *"
			String[] toks = t.split("\\s+");
			if (toks.length == 0)
				continue;
			// If last token contains '*' or digits, could be part of type; otherwise assume it's a name and remove it
			String last = toks[toks.length - 1];
			if (isParamName(last)) {
				// drop last token
				String[] typeToks = Arrays.copyOf(toks, toks.length - 1);
				String type = String.join(" ", typeToks).trim();
				if (type.isEmpty()) {
					// fallback to unknown
					res.add("unknown");
				} else {
					res.add(normalizeType(type));
				}
			} else {
				// treat whole as type (e.g., "int" or "char*")
				res.add(normalizeType(t));
			}
		}
		return res;
	}

	private boolean isParamName(String token) {
		// heuristics: if token contains letters and not '*' and not something like 'const'
		// parameter names usually are simple identifiers possibly with array suffixes.
		// If token contains '*' it's more likely part of type.
		if (token.contains("*"))
			return false;
		// if token includes digits only or starts with '[' treat as name
		if (token.matches("\\[.*") || token.matches(".*\\]"))
			return true;
		// token is identifier-like -> consider it parameter name
		return token.matches("[A-Za-z_][A-Za-z0-9_]*");
	}

	private String normalizeType(String raw) {
		String t = raw.replaceAll("\\s+", " ").trim();
		// move pointer stars next to base type: "char *" -> "char *"
		t = t.replaceAll("\\s*\\*\\s*", " *");
		return t;
	}
}