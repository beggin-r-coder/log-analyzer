import java.lang.reflect.*;
import java.util.*;

/** Dumps the generator, playbook and classifier data from the compiled app as JSON (via reflection). */
public class ExportDemoData {
	static String q(Object o) {
		if (o == null) return "null";
		if (o instanceof Number || o instanceof Boolean) return o.toString();
		String s = o.toString();
		StringBuilder b = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			switch (c) {
			case '"' -> b.append("\\\"");
			case '\\' -> b.append("\\\\");
			case '\n' -> b.append("\\n");
			case '\t' -> b.append("\\t");
			default -> { if (c < 0x20 || c > 0x7e) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
			}
		}
		return b.append('"').toString();
	}
	static Object call(Object target, String accessor) throws Exception {
		Method m = target.getClass().getDeclaredMethod(accessor);
		m.setAccessible(true);
		return m.invoke(target);
	}
	static Object field(Class<?> c, String name) throws Exception {
		Field f = c.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(null);
	}
	static String list(List<?> l) {
		StringJoiner j = new StringJoiner(",", "[", "]");
		for (Object o : l) j.add(q(o));
		return j.toString();
	}
	static String msgs(List<?> l) throws Exception {
		StringJoiner j = new StringJoiner(",", "[", "]");
		for (Object m : l) j.add("{\"text\":" + q(call(m, "text")) + ",\"logger\":" + q(call(m, "logger")) + ",\"stackTrace\":" + call(m, "stackTrace") + "}");
		return j.toString();
	}
	static String play(Object p) throws Exception {
		return "{\"id\":" + q(call(p, "id")) + ",\"title\":" + q(call(p, "title")) + ",\"rootCause\":" + q(call(p, "rootCause"))
				+ ",\"impact\":" + q(call(p, "impact")) + ",\"recovery\":" + list((List<?>) call(p, "recovery"))
				+ ",\"prevention\":" + list((List<?>) call(p, "prevention")) + ",\"severity\":" + q(call(p, "severity")) + "}";
	}
	public static void main(String[] a) throws Exception {
		Class<?> gen = Class.forName("com.anishka.log_analyzer.simulator.BfsiLogGenerator");
		Method build = gen.getDeclaredMethod("buildProfiles");
		build.setAccessible(true);
		StringJoiner profiles = new StringJoiner(",", "[", "]");
		for (Object p : (List<?>) build.invoke(null)) {
			profiles.add("{\"name\":" + q(call(p, "name")) + ",\"format\":" + q(call(p, "format")) + ",\"weight\":" + call(p, "weight")
					+ ",\"hostPrefix\":" + q(call(p, "hostPrefix")) + ",\"info\":" + msgs((List<?>) call(p, "info")) + ",\"warn\":" + msgs((List<?>) call(p, "warn"))
					+ ",\"error\":" + msgs((List<?>) call(p, "error")) + ",\"fatal\":" + msgs((List<?>) call(p, "fatal")) + ",\"incident\":" + msgs((List<?>) call(p, "incident")) + "}");
		}
		StringBuilder out = new StringBuilder("{");
		out.append("\"profiles\":").append(profiles);
		for (String arr : List.of("NB_OK_PATHS", "USER_AGENTS", "PAYMENT_METHODS", "LOAN_TYPES", "PSP", "FX_PAIRS")) {
			out.append(",\"").append(arr).append("\":").append(list(Arrays.asList((String[]) field(gen, arr))));
		}
		Class<?> sim = Class.forName("com.anishka.log_analyzer.simulator.LiveLogSimulator");
		out.append(",\"HOURLY_TRAFFIC\":").append(Arrays.toString((double[]) field(sim, "HOURLY_TRAFFIC")));
		Class<?> pb = Class.forName("com.anishka.log_analyzer.ai.RecoveryPlaybook");
		StringJoiner rules = new StringJoiner(",", "[", "]");
		for (Object r : (List<?>) field(pb, "RULES")) rules.add("{\"anyOf\":" + list((List<?>) call(r, "anyOf")) + ",\"play\":" + play(call(r, "play")) + "}");
		out.append(",\"PLAYBOOK\":").append(rules).append(",\"GENERIC_PLAY\":").append(play(field(pb, "GENERIC")));
		Class<?> cl = Class.forName("com.anishka.log_analyzer.service.ErrorClassifier");
		StringJoiner cr = new StringJoiner(",", "[", "]");
		for (Map.Entry<?, ?> e : ((Map<?, ?>) field(cl, "RULES")).entrySet()) cr.add("[" + q(e.getKey()) + "," + list((List<?>) e.getValue()) + "]");
		out.append(",\"CLASSIFIER\":").append(cr);
		Class<?> lf = Class.forName("com.anishka.log_analyzer.parser.LogFormat");
		StringJoiner labels = new StringJoiner(",", "{", "}");
		for (Object c : lf.getEnumConstants()) labels.add(q(((Enum<?>) c).name()) + ":" + q(call(c, "getLabel")));
		out.append(",\"FORMAT_LABELS\":").append(labels).append("}");
		System.out.println(out);
	}
}
