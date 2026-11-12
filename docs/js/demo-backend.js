/* Log Analyzer – static demo backend.
 *
 * The GitHub Pages demo has no Java server, so this file answers the page's /api/* requests and the
 * live-stream connection inside the browser. It ports the Spring Boot backend: the BFSI log generator,
 * the universal log parser, the error classifier, the dashboard aggregations, log search, and the
 * NLP error analysis (pattern mining, entities, TF-IDF similarity, failure playbook).
 * Generator templates, playbook and classifier rules come from the Java classes (js/demo-data.js).
 */
(function () {
	'use strict';

	const D = window.DEMO_DATA;
	const SEED_DAYS = 2, AVG_PER_MINUTE = 12, INTERVAL_MS = 2000, BUSINESS_OFFSET_MIN = 330; // Asia/Kolkata
	const MIN = 60000, HOUR = 3600000;

	// =====================================================================
	// SMALL HELPERS
	// =====================================================================
	const rnd = Math.random;
	const int = (n) => Math.floor(rnd() * n);
	const pick = (a) => a[int(a.length)];
	const pad = (n, w = 2) => String(n).padStart(w, '0');
	const digits = (n) => { let s = ''; for (let i = 0; i < n; i++) s += int(10); return s; };
	const hex = (n) => { let s = ''; for (let i = 0; i < n; i++) s += '0123456789abcdef'[int(16)]; return s; };
	const ip = () => int(3) === 0 ? `10.20.${int(255)}.${1 + int(254)}` : `${49 + int(150)}.${int(255)}.${int(255)}.${1 + int(254)}`;
	const money = (v) => v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
	const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
	const cut = (s, max) => s == null || s.length <= max ? s : s.slice(0, max - 1) + '…';
	const blank = (s) => { if (s == null) return null; const t = String(s).trim(); return t === '' || t === '-' ? null : t; };
	const round2 = (v) => Math.round(v * 100) / 100;

	// ---------- local-time formatting (the browser plays the role of the servers) ----------
	const offMin = (ms) => -new Date(ms).getTimezoneOffset();
	const offStr = (ms, colon) => { const o = offMin(ms); if (o === 0 && colon) return 'Z'; const s = o >= 0 ? '+' : '-', a = Math.abs(o); return s + pad(Math.floor(a / 60)) + (colon ? ':' : '') + pad(a % 60); };
	const parts = (ms) => { const d = new Date(ms); return { y: d.getFullYear(), mo: d.getMonth(), d: d.getDate(), h: d.getHours(), mi: d.getMinutes(), s: d.getSeconds(), ms: d.getMilliseconds() }; };
	const isoLocal = (ms) => { const p = parts(ms); return `${p.y}-${pad(p.mo + 1)}-${pad(p.d)}T${pad(p.h)}:${pad(p.mi)}:${pad(p.s)}.${pad(p.ms, 3)}`; };
	const isoOffset = (ms) => isoLocal(ms) + offStr(ms, true);
	const isoUtc = (ms) => new Date(ms).toISOString();
	const log4jTs = (ms) => { const p = parts(ms); return `${p.y}-${pad(p.mo + 1)}-${pad(p.d)} ${pad(p.h)}:${pad(p.mi)}:${pad(p.s)},${pad(p.ms, 3)}`; };
	const pipeTs = (ms) => { const p = parts(ms); return `${p.y}/${pad(p.mo + 1)}/${pad(p.d)} ${pad(p.h)}:${pad(p.mi)}:${pad(p.s)}.${pad(p.ms, 3)}`; };
	const dmyTs = (ms) => { const p = parts(ms); return `${pad(p.d)}-${pad(p.mo + 1)}-${p.y} ${pad(p.h)}:${pad(p.mi)}:${pad(p.s)}`; };
	const accessTs = (ms) => { const p = parts(ms); return `${pad(p.d)}/${MONTHS[p.mo]}/${p.y}:${pad(p.h)}:${pad(p.mi)}:${pad(p.s)} ${offStr(ms, false)}`; };
	const syslogTs = (ms) => { const p = parts(ms); return `${MONTHS[p.mo]} ${String(p.d).padStart(2, ' ')} ${pad(p.h)}:${pad(p.mi)}:${pad(p.s)}`; };
	const zoneName = (ms) => { try { return new Intl.DateTimeFormat('en-US', { timeZoneName: 'short' }).formatToParts(new Date(ms)).find((x) => x.type === 'timeZoneName').value; } catch (e) { return ''; } };
	const fmtZ = (ms) => { const p = parts(ms); return `${p.y}-${pad(p.mo + 1)}-${pad(p.d)} ${pad(p.h)}:${pad(p.mi)}:${pad(p.s)} ${zoneName(ms)}`; };

	// =====================================================================
	// BFSI LOG GENERATOR (port of BfsiLogGenerator)
	// =====================================================================
	const PROFILES = D.profiles;
	const TOTAL_WEIGHT = PROFILES.reduce((a, p) => a + p.weight, 0);
	const BUSY = PROFILES.filter((p) => p.weight >= 10).map((p) => p.name);

	function value(key) {
		switch (key) {
			case 'acct': return 'XXXXXX' + digits(4);
			case 'card': return (rnd() < 0.5 ? '4' : '5') + 'XXXXXXXXXXX' + digits(4);
			case 'amt': { const x = rnd(); return money(x < 0.6 ? 50 + rnd() * 5000 : x < 0.9 ? 5000 + rnd() * 95000 : 100000 + rnd() * 1900000); }
			case 'txn': return 'TXN' + digits(12);
			case 'upitxn': return 'NOVA' + digits(14);
			case 'rrn': return digits(12);
			case 'vpa': return String.fromCharCode(97 + int(26)) + '****' + digits(2) + '@' + pick(D.PSP);
			case 'ms': return String(40 + int(2400));
			case 'slowms': return String(900 + int(4000));
			case 'pct': return String(70 + int(29));
			case 'lowpct': return String(5 + int(12));
			case 'num': return digits(7);
			case 'cif': return digits(9);
			case 'mid': return digits(6);
			case 'order': return 'ORD' + digits(10);
			case 'ifsc': return 'NOVA000' + digits(4);
			case 'atm': return 'ATM' + digits(5);
			case 'n': return String(5 + int(60));
			case 'big': return (1000 + int(90000)).toLocaleString('en-US');
			case 'score': return String(300 + int(600));
			case 'fraudscore': return String(int(400));
			case 'months': return String(pick([12, 24, 36, 60]));
			case 'method': return pick(D.PAYMENT_METHODS);
			case 'loantype': return pick(D.LOAN_TYPES);
			case 'pair': return pick(D.FX_PAIRS);
			case 'rate': return (83 + rnd() * 2).toFixed(4);
			case 'code': return digits(6);
			case 'sess': return hex(12);
			case 'ip': return ip();
			default: return '{' + key + '}';
		}
	}
	const fill = (tpl) => tpl.replace(/\{([a-z]+)\}/g, (_, k) => value(k));

	function pickProfile(incidentService) {
		const boosted = incidentService ? PROFILES.find((p) => p.name === incidentService) : null;
		const boost = boosted ? TOTAL_WEIGHT * 0.25 : 0; // retries make the failing service noisier
		let x = rnd() * (TOTAL_WEIGHT + boost);
		if (boosted && x >= TOTAL_WEIGHT) return boosted;
		for (const p of PROFILES) { x -= p.weight; if (x < 0) return p; }
		return PROFILES[0];
	}

	const syslogSeverity = (l) => ({ FATAL: 2, ERROR: 3, WARN: 4, DEBUG: 7 })[l] ?? 6;
	const shortLevel = (l) => ({ ERROR: 'ERR', WARN: 'WRN', DEBUG: 'DBG', FATAL: 'FTL' })[l] || 'INF';

	function accessLog(ts, level, addr) {
		let req, status;
		if (level === 'ERROR' || level === 'FATAL') {
			req = pick(['POST /api/v1/fund-transfer', 'POST /api/v1/upi/collect', 'GET /api/v1/accounts/summary', 'POST /api/v1/bill-pay']);
			status = pick([500, 502, 503, 504]);
		} else if (level === 'WARN') {
			req = pick(['POST /netbanking/login', 'GET /api/v1/cards', 'POST /api/v1/beneficiary/add', 'GET /admin']);
			status = pick([401, 403, 404, 429]);
		} else {
			req = pick(D.NB_OK_PATHS);
			status = int(10) === 0 ? 302 : 200;
		}
		const rt = status >= 500 ? 2 + rnd() * 28 : rnd() * 0.8;
		return `${addr} - - [${accessTs(ts)}] "${req} HTTP/1.1" ${status} ${200 + int(18000)} "-" "${pick(D.USER_AGENTS)}" rt=${rt.toFixed(3)}`;
	}

	function formatLine(p, ts, level, text, logger, trace, host) {
		const addr = ip();
		switch (p.format) {
			case 'SPRING_BOOT': return `${isoOffset(ts)} ${level.padStart(5)} ${4200 + int(80)} --- [${p.name}] [nio-8080-exec-${1 + int(20)}] ${logger.padEnd(40)} : ${text} traceId=${trace}`;
			case 'BRACKETED': return `${isoLocal(ts)} [${level}] [${p.name}] [req-${hex(8)}] [${addr}] ${text}`;
			case 'JSON':
				return p.name === 'mobile-banking-api'
					? JSON.stringify({ '@timestamp': isoOffset(ts), log: { level: level.toLowerCase(), logger }, service: { name: p.name, version: '4.3.0' }, host: { name: host }, trace: { id: trace }, client: { ip: addr }, message: text })
					: JSON.stringify({ timestamp: isoOffset(ts), level, service: p.name, host, logger, traceId: trace, message: text });
			case 'LOGFMT': return `ts=${isoUtc(ts)} level=${level.toLowerCase()} service=${p.name} host=${host} trace_id=${trace} msg="${text.replace(/"/g, '\\"')}"`;
			case 'ACCESS_LOG': return accessLog(ts, level, addr);
			case 'SYSLOG_RFC3164': return `${syslogTs(ts)} ${host} ${p.name}[${2000 + int(900)}]: ${level} ${text}`;
			case 'LOG4J': return `${log4jTs(ts)} ${level.padEnd(5)} [http-nio-8443-exec-${1 + int(16)}] ${logger} - ${text}`;
			case 'PYTHON': return `${log4jTs(ts)} - ${p.name} - ${level === 'WARN' ? 'WARNING' : level === 'FATAL' ? 'CRITICAL' : level} - ${text}`;
			case 'SYSLOG_RFC5424': return `<${16 * 8 + syslogSeverity(level)}>1 ${isoOffset(ts)} ${host} ${p.name} ${3300 + int(50)} - - ${text}`;
			case 'PIPE_DELIMITED': return `${pipeTs(ts)} | ${level} | ${p.name} | ${text}`;
			default: return `${dmyTs(ts)} ${shortLevel(level)} ${text}`;
		}
	}

	function generate(ts, incidentService) {
		const p = pickProfile(incidentService);
		const inIncident = p.name === incidentService;
		const roll = rnd();
		let level, msg;
		if (inIncident && roll < 0.55) { level = 'ERROR'; msg = pick(p.incident); }
		else if (roll < 0.0012 && p.fatal.length) { level = 'FATAL'; msg = pick(p.fatal); }
		else if (roll < 0.0135) { level = 'ERROR'; msg = pick(p.error); }
		else if (roll < 0.075) { level = 'WARN'; msg = pick(p.warn); }
		else if (roll < 0.095) { level = 'DEBUG'; msg = pick(p.info); }
		else { level = 'INFO'; msg = pick(p.info); }
		const text = fill(msg.text);
		let raw = formatLine(p, ts, level, text, msg.logger, hex(16), `${p.hostPrefix}-0${1 + int(3)}`);
		if (msg.stackTrace) {
			const cls = msg.logger.slice(msg.logger.lastIndexOf('.') + 1);
			raw += `\n\tat ${msg.logger}.process(${cls}.java:${80 + int(200)})\n\tat com.novabank.common.web.TxnFilter.doFilter(TxnFilter.java:57)\n\tat org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:885)\n\t... 42 more`;
		}
		return { service: p.name, raw };
	}

	// =====================================================================
	// UNIVERSAL LOG PARSER (port of UniversalLogParser)
	// =====================================================================
	const TS_ISO = '\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?(?:Z|[+-]\\d{2}:?\\d{2})?';
	const TS_SLASH = '\\d{4}/\\d{2}/\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?';
	const TS_TIME_ONLY = '\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?';
	const TS_ANY = '(?:' + TS_ISO + '|' + TS_SLASH + ')';
	const LEVEL = '(?:TRACE|DEBUG|INFO|NOTICE|WARN(?:ING)?|ERROR|ERR|FATAL|CRIT(?:ICAL)?|SEVERE|ALERT|EMERG|DBG|WRN|INF|FTL)';
	const R = {
		spring: new RegExp('^(' + TS_ANY + ')\\s+(' + LEVEL + ')\\s+(\\d+)\\s+---\\s+(?:\\[([^\\]]+)\\]\\s+)?\\[\\s*([^\\]]+)\\]\\s+(\\S+)\\s+:\\s+(.*)$'),
		bracketed: new RegExp('^(' + TS_ANY + ')\\s+\\[(' + LEVEL + ')\\]\\s+\\[(.*?)\\]\\s+\\[(.*?)\\]\\s+\\[(.*?)\\]\\s+(.*)$'),
		log4jLevelFirst: new RegExp('^(' + TS_ANY + ')\\s+(' + LEVEL + ')\\s+\\[([^\\]]+)\\]\\s+(\\S+)\\s+-\\s+(.*)$'),
		log4jThreadFirst: new RegExp('^(' + TS_ANY + '|' + TS_TIME_ONLY + ')\\s+\\[([^\\]]+)\\]\\s+(' + LEVEL + ')\\s+(\\S+)\\s+-\\s+(.*)$'),
		python: new RegExp('^(' + TS_ANY + ')\\s+-\\s+(\\S+)\\s+-\\s+(' + LEVEL + ')\\s+-\\s+(.*)$'),
		pipe: new RegExp('^(' + TS_ANY + ')\\s*\\|\\s*(' + LEVEL + ')\\s*\\|\\s*([^|]+?)\\s*\\|\\s*(.*)$'),
		access: /^(\S+)\s+\S+\s+(\S+)\s+\[([^\]]+)\]\s+"([A-Z]+)\s+(\S+)[^"]*"\s+(\d{3})\s+(\S+)(?:\s+"([^"]*)"\s+"([^"]*)")?(.*)$/,
		apacheError: /^\[(\w{3} \w{3} \d{1,2} \d{2}:\d{2}:\d{2}(?:\.\d+)? \d{4})\]\s+\[(?:([\w-]+):)?(\w+)\]\s+(?:\[pid (\d+)[^\]]*\]\s+)?(?:\[client ([^\]]+)\]\s+)?(.*)$/,
		syslog5424: /^<(\d{1,3})>1\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(-|\[.*?\])\s*(.*)$/,
		syslog3164: /^(?:<(\d{1,3})>)?([A-Z][a-z]{2}\s+\d{1,2}\s+\d{2}:\d{2}:\d{2})\s+(\S+)\s+([\w./-]+?)(?:\[(\d+)\])?:\s+(.*)$/,
		logfmtStart: /^([A-Za-z_@][\w.@-]*)=("(?:[^"\\]|\\.)*"|\S*)/,
		logfmtPair: /([A-Za-z_@][\w.@-]*)=("(?:[^"\\]|\\.)*"|\S*)/g,
		anyTs: new RegExp('(' + TS_ANY + ')|(\\d{2}/[A-Z][a-z]{2}/\\d{4}:\\d{2}:\\d{2}:\\d{2}(?: [+-]\\d{4})?)|([A-Z][a-z]{2}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2})|(\\d{2}-\\d{2}-\\d{4} \\d{2}:\\d{2}:\\d{2})'),
		levelToken: new RegExp('(?<![\\w-])(' + LEVEL + ')(?![\\w-])'),
		leadingLevel: new RegExp('^\\[?(' + LEVEL + ')\\]?[:\\s]+(.*)$', 's'),
		traceId: /\b(?:trace[_-]?id|request[_-]?id|req[_-]?id|correlation[_-]?id|txn[_-]?id|x-request-id|rrn)\s*[=:]\s*"?([\w-]{6,64})/i,
		ipv4: /\b((?:25[0-5]|2[0-4]\d|1?\d?\d)(?:\.(?:25[0-5]|2[0-4]\d|1?\d?\d)){3})\b/,
		continuation: /^(?:\s+at\s|\s+\.\.\.\s*\d+\s+more|Caused by:|\s{2,}\S|\t)/
	};

	function normalizeLevel(raw) {
		if (raw == null || String(raw).trim() === '') return null;
		const l = String(raw).trim().toUpperCase();
		if (l.startsWith('TRACE')) return 'TRACE';
		if (['DEBUG', 'DBG', 'FINE', 'FINER', 'FINEST', 'VERBOSE'].includes(l)) return 'DEBUG';
		if (['INFO', 'INF', 'INFORMATION', 'NOTICE', 'CONFIG'].includes(l)) return 'INFO';
		if (['WARN', 'WARNING', 'WRN'].includes(l)) return 'WARN';
		if (['ERROR', 'ERR', 'SEVERE'].includes(l)) return 'ERROR';
		if (['FATAL', 'FTL', 'CRIT', 'CRITICAL', 'ALERT', 'EMERG', 'EMERGENCY', 'PANIC'].includes(l)) return 'FATAL';
		return null;
	}
	const levelRank = (l) => ({ TRACE: 0, DEBUG: 1, WARN: 3, ERROR: 4, FATAL: 5 })[l] ?? 2;
	const levelFromPri = (pri) => { const s = Number(pri) % 8; return s <= 2 ? 'FATAL' : s === 3 ? 'ERROR' : s === 4 ? 'WARN' : s === 7 ? 'DEBUG' : 'INFO'; };
	function guessLevel(text) {
		const t = (text || '').toLowerCase();
		if (t.includes('fatal') || t.includes('panic')) return 'FATAL';
		if (t.includes('exception') || t.includes('error') || t.includes('failed') || t.includes('failure')) return 'ERROR';
		if (t.includes('warn') || t.includes('retry') || t.includes('deprecated')) return 'WARN';
		return 'INFO';
	}

	/** Timestamp text to epoch millis; values without a zone are taken as local time. */
	function parseTimestamp(raw) {
		if (raw == null || String(raw).trim() === '') return null;
		let s = String(raw).trim();
		if (/^\d{10}(\d{3})?$/.test(s)) return s.length === 13 ? Number(s) : Number(s) * 1000;
		if (new RegExp('^' + TS_TIME_ONLY + '$').test(s)) {
			const [h, m, sec] = s.replace(',', '.').split(':');
			const d = new Date(); d.setHours(Number(h), Number(m), 0, 0);
			return d.getTime() + Math.round(parseFloat(sec) * 1000);
		}
		s = s.replace(/\//g, '-').replace(',', '.');
		if (s.length > 10 && s[10] === ' ') s = s.slice(0, 10) + 'T' + s.slice(11);
		s = s.replace(/([+-]\d{2})(\d{2})$/, '$1:$2');
		const m = s.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})?$/);
		if (!m) return null;
		const msPart = m[7] ? Number((m[7] + '00').slice(0, 3)) : 0;
		if (m[8]) {
			const base = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6], msPart);
			if (m[8] === 'Z') return base;
			const sign = m[8][0] === '-' ? -1 : 1, [oh, om] = m[8].slice(1).split(':').map(Number);
			return base - sign * (oh * 60 + om) * MIN;
		}
		return new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6], msPart).getTime();
	}
	function parseAccessTs(v) {
		const m = v.match(/^(\d{2})\/([A-Z][a-z]{2})\/(\d{4}):(\d{2}):(\d{2}):(\d{2})(?: ([+-])(\d{2})(\d{2}))?$/);
		if (!m) return null;
		const mo = MONTHS.indexOf(m[2]);
		if (mo < 0) return null;
		if (!m[7]) return new Date(+m[3], mo, +m[1], +m[4], +m[5], +m[6]).getTime();
		return Date.UTC(+m[3], mo, +m[1], +m[4], +m[5], +m[6]) - (m[7] === '-' ? -1 : 1) * (+m[8] * 60 + +m[9]) * MIN;
	}
	function parseSyslogTs(v) {
		const m = v.replace(/\s+/g, ' ').match(/^([A-Z][a-z]{2}) (\d{1,2}) (\d{2}):(\d{2}):(\d{2})$/);
		if (!m || MONTHS.indexOf(m[1]) < 0) return null;
		const now = new Date();
		let t = new Date(now.getFullYear(), MONTHS.indexOf(m[1]), +m[2], +m[3], +m[4], +m[5]).getTime();
		if (t > Date.now() + 24 * HOUR) t = new Date(now.getFullYear() - 1, MONTHS.indexOf(m[1]), +m[2], +m[3], +m[4], +m[5]).getTime();
		return t;
	}
	function parseApacheTs(v) {
		const m = v.replace(/\s+/g, ' ').match(/^\w{3} ([A-Z][a-z]{2}) (\d{1,2}) (\d{2}):(\d{2}):(\d{2})(?:\.(\d+))? (\d{4})$/);
		if (!m || MONTHS.indexOf(m[1]) < 0) return null;
		return new Date(+m[7], MONTHS.indexOf(m[1]), +m[2], +m[3], +m[4], +m[5], m[6] ? Number((m[6] + '00').slice(0, 3)) : 0).getTime();
	}

	function flatten(prefix, obj, out) {
		for (const [k, v] of Object.entries(obj)) {
			const key = (prefix + k).toLowerCase();
			if (v && typeof v === 'object' && !Array.isArray(v)) flatten(key + '.', v, out);
			else if (!(key in out)) out[key] = v;
		}
		return out;
	}
	const first = (map, ...keys) => { for (const k of keys) { const v = map[k]; if (v != null && !(typeof v === 'string' && v.trim() === '')) return v; } return null; };
	const str = (o) => o == null ? null : String(o);

	function rec(format, f) {
		return Object.assign({ format, timestamp: null, level: null, service: null, host: null, thread: null, logger: null, traceId: null, clientIp: null, message: '' }, f);
	}

	function tryJson(line) {
		if (!line.startsWith('{') || !line.endsWith('}')) return null;
		let flat;
		try { const o = JSON.parse(line); if (!o || typeof o !== 'object' || Array.isArray(o)) return null; flat = flatten('', o, {}); } catch (e) { return null; }
		const ts = first(flat, '@timestamp', 'timestamp', 'time', 'ts', 'datetime', 'date', 'eventtime', 't');
		return rec('JSON', {
			timestamp: typeof ts === 'number' ? (ts > 100000000000 ? ts : ts * 1000) : parseTimestamp(str(ts)),
			level: normalizeLevel(str(first(flat, 'level', 'log.level', 'severity', 'loglevel', 'lvl', 'levelname', 'severity_text'))),
			message: str(first(flat, 'message', 'msg', 'log', 'text', 'event', 'error.message')) || '',
			service: str(first(flat, 'service', 'service.name', 'app', 'application', 'appname', 'component')),
			host: str(first(flat, 'host', 'hostname', 'host.name', 'pod')),
			thread: str(first(flat, 'thread', 'thread_name', 'threadname')),
			logger: str(first(flat, 'logger', 'logger_name', 'loggername', 'log.logger', 'name')),
			traceId: str(first(flat, 'traceid', 'trace_id', 'trace.id', 'requestid', 'request_id', 'correlationid', 'correlation_id', 'txnid', 'x-request-id')),
			clientIp: str(first(flat, 'client_ip', 'clientip', 'ip', 'remote_addr', 'client.ip', 'source.ip'))
		});
	}

	function tryLogfmt(line) {
		if (!R.logfmtStart.test(line)) return null;
		const kv = {};
		for (const m of line.matchAll(R.logfmtPair)) {
			let v = m[2];
			if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) v = v.slice(1, -1).replace(/\\"/g, '"');
			kv[m[1].toLowerCase()] = v;
		}
		if (Object.keys(kv).length < 2 || (first(kv, 'msg', 'message') == null && first(kv, 'level', 'lvl', 'severity') == null)) return null;
		return rec('LOGFMT', {
			timestamp: parseTimestamp(first(kv, 'ts', 'time', 'timestamp', 't', '@timestamp')),
			level: normalizeLevel(first(kv, 'level', 'lvl', 'severity')),
			message: first(kv, 'msg', 'message', 'error', 'err') || '',
			service: first(kv, 'service', 'app', 'svc', 'component'), host: first(kv, 'host', 'hostname', 'pod'),
			thread: first(kv, 'thread', 'goroutine'), logger: first(kv, 'logger', 'caller', 'source'),
			traceId: first(kv, 'trace_id', 'traceid', 'request_id', 'req_id', 'correlation_id', 'txn_id'),
			clientIp: first(kv, 'client_ip', 'ip', 'remote_addr', 'src_ip')
		});
	}

	function parseGeneric(line) {
		let rest = line, timestamp = null, level;
		const tm = line.match(R.anyTs);
		if (tm) {
			if (tm[1]) timestamp = parseTimestamp(tm[1]);
			else if (tm[2]) timestamp = parseAccessTs(tm[2]);
			else if (tm[3]) timestamp = parseSyslogTs(tm[3]);
			else if (tm[4]) { const m = tm[4].match(/^(\d{2})-(\d{2})-(\d{4}) (\d{2}):(\d{2}):(\d{2})$/); timestamp = new Date(+m[3], +m[2] - 1, +m[1], +m[4], +m[5], +m[6]).getTime(); }
			rest = rest.slice(0, tm.index) + rest.slice(tm.index + tm[0].length);
		}
		const lm = rest.match(R.levelToken);
		if (lm) { level = normalizeLevel(lm[1]); rest = rest.slice(0, lm.index) + rest.slice(lm.index + lm[0].length); }
		else level = guessLevel(line);
		rest = rest.replace(/^[\s[\]|:\-,]+/, '').replace(/\[\s*\]/g, '').replace(/\s{2,}/g, ' ').trim();
		return rec('GENERIC', { timestamp, level, message: rest || line });
	}

	function parseLine(rawLine, defaultService) {
		if (rawLine == null || rawLine.trim() === '') return null;
		const line = rawLine.trim();
		let p = tryJson(line), m;
		if (!p && (m = line.match(R.spring))) p = rec('SPRING_BOOT', { timestamp: parseTimestamp(m[1]), level: normalizeLevel(m[2]), service: m[4], thread: m[5], logger: m[6], message: m[7] });
		if (!p && (m = line.match(R.bracketed))) p = rec('BRACKETED', { timestamp: parseTimestamp(m[1]), level: normalizeLevel(m[2]), service: m[3], traceId: m[4], clientIp: m[5], message: m[6] });
		if (!p && (m = line.match(R.log4jLevelFirst))) p = rec('LOG4J', { timestamp: parseTimestamp(m[1]), level: normalizeLevel(m[2]), thread: m[3], logger: m[4], message: m[5] });
		if (!p && (m = line.match(R.log4jThreadFirst))) p = rec('LOG4J', { timestamp: parseTimestamp(m[1]), thread: m[2], level: normalizeLevel(m[3]), logger: m[4], message: m[5] });
		if (!p && (m = line.match(R.python))) p = rec('PYTHON', { timestamp: parseTimestamp(m[1]), logger: m[2], service: m[2], level: normalizeLevel(m[3]), message: m[4] });
		if (!p && (m = line.match(R.pipe))) p = rec('PIPE_DELIMITED', { timestamp: parseTimestamp(m[1]), level: normalizeLevel(m[2]), service: m[3], message: m[4] });
		if (!p && (m = line.match(R.access))) {
			const ts = parseAccessTs(m[3]);
			if (ts != null) {
				const status = Number(m[6]), extra = (m[10] || '').trim();
				p = rec('ACCESS_LOG', {
					timestamp: ts, level: status >= 500 ? 'ERROR' : status >= 400 ? 'WARN' : 'INFO', clientIp: m[1],
					message: `${m[4]} ${m[5]} -> HTTP ${status}${m[7] === '-' ? '' : ' (' + m[7] + ' bytes)'}${extra ? ' ' + extra : ''}`
				});
			}
		}
		if (!p && (m = line.match(R.apacheError))) {
			const ts = parseApacheTs(m[1]);
			if (ts != null) {
				let client = m[5];
				if (client && client.includes(':') && client.indexOf(':') === client.lastIndexOf(':')) client = client.slice(0, client.indexOf(':'));
				p = rec('APACHE_ERROR', { timestamp: ts, logger: m[2], level: normalizeLevel(m[3]), thread: m[4], clientIp: client, service: 'apache-httpd', message: m[6] });
			}
		}
		if (!p && (m = line.match(R.syslog5424))) {
			let msg = m[8], level = levelFromPri(m[1]);
			const lead = msg.match(R.leadingLevel);
			if (lead) { level = normalizeLevel(lead[1]); msg = lead[2]; }
			p = rec('SYSLOG_RFC5424', { timestamp: parseTimestamp(m[2]), host: m[3], service: m[4], thread: m[5], level, message: msg });
		}
		if (!p && (m = line.match(R.syslog3164))) {
			const ts = parseSyslogTs(m[2]);
			if (ts != null) {
				let msg = m[6], level = m[1] ? levelFromPri(m[1]) : null;
				const lead = msg.match(R.leadingLevel);
				if (lead) { level = normalizeLevel(lead[1]); msg = lead[2]; }
				p = rec('SYSLOG_RFC3164', { timestamp: ts, host: m[3], service: m[4], thread: m[5], level: level || guessLevel(msg), message: msg });
			}
		}
		if (!p) p = tryLogfmt(line);
		if (!p) p = parseGeneric(line);

		['service', 'host', 'thread', 'logger', 'traceId', 'clientIp'].forEach((k) => { p[k] = blank(p[k]); });
		p.message = (p.message == null ? '' : String(p.message)).trim();
		p.rawLine = line;
		if (p.timestamp == null || Number.isNaN(p.timestamp)) p.timestamp = Date.now();
		if (!p.level) p.level = guessLevel(p.message);
		if (!p.service) p.service = defaultService || 'unknown';
		if (!p.traceId) { const t = p.message.match(R.traceId); if (t) p.traceId = t[1]; }
		if (!p.clientIp) { const t = p.message.match(R.ipv4); if (t) p.clientIp = t[1]; }
		return p;
	}

	function parseText(text, defaultService) {
		const out = [];
		let current = null;
		for (const line of String(text || '').split(/\r?\n/)) {
			if (line.trim() === '') continue;
			if (current && R.continuation.test(line)) {
				current.message += '\n' + line.trimEnd();
				current.rawLine += '\n' + line.trimEnd();
				continue;
			}
			current = parseLine(line, defaultService);
			if (current) out.push(current);
		}
		return out;
	}

	// ---------- classifier ----------
	function classify(message) {
		const m = (message || '').toLowerCase();
		for (const [cat, words] of D.CLASSIFIER) if (words.some((w) => m.includes(w))) return cat;
		return 'Other Errors';
	}

	// =====================================================================
	// STORE
	// =====================================================================
	const store = { records: [], byId: new Map(), nextId: 1, listeners: new Set() };

	function toRecord(p, preview) {
		const rank = levelRank(p.level);
		return {
			id: preview ? null : store.nextId++, timestamp: p.timestamp, level: p.level, levelRank: rank, service: cut(p.service, 64), host: cut(p.host, 64),
			thread: cut(p.thread, 64), logger: cut(p.logger, 128), traceId: cut(p.traceId, 64), clientIp: cut(p.clientIp, 45),
			message: cut(p.message, 2000), category: rank >= 3 ? classify(p.message) : null, format: p.format, rawLine: cut(p.rawLine, 4000)
		};
	}

	function save(parsed, notify) {
		const saved = parsed.map(toRecord);
		for (const r of saved) { store.records.push(r); store.byId.set(r.id, r); }
		if (notify && saved.length) {
			const dtos = saved.slice(-200).map(dto);
			store.listeners.forEach((fn) => fn(dtos));
		}
		return saved;
	}

	const dto = (r) => ({
		id: r.id, timestamp: r.timestamp, timestampIso: isoLocal(r.timestamp), level: r.level, service: r.service, host: r.host, thread: r.thread,
		logger: r.logger, traceId: r.traceId, clientIp: r.clientIp, message: r.message, category: r.category, format: r.format,
		formatLabel: D.FORMAT_LABELS[r.format] || r.format, rawLine: r.rawLine
	});

	// =====================================================================
	// SIMULATOR (port of LiveLogSimulator)
	// =====================================================================
	const sim = { running: true, incident: null };

	function trafficWeight(t) {
		const biz = new Date(t + BUSINESS_OFFSET_MIN * MIN);
		let w = D.HOURLY_TRAFFIC[biz.getUTCHours()];
		const day = biz.getUTCDay();
		if (day === 0 || day === 6) w *= 0.7;
		return w;
	}
	function poisson(lambda) {
		if (lambda <= 0) return 0;
		const l = Math.exp(-lambda);
		let k = 0, p = 1;
		do { k++; p *= rnd(); } while (p > l);
		return k - 1;
	}
	const services = () => PROFILES.map((p) => p.name);

	function seed() {
		const end = Date.now(), start = Math.floor((end - SEED_DAYS * 24 * HOUR) / MIN) * MIN;
		const incidents = [];
		for (let h = start; h < end - 3 * HOUR; h += HOUR) {
			if (rnd() < 0.07) { const s = h + int(60) * MIN; incidents.push({ service: pick(services()), start: s, end: s + (4 + int(18)) * MIN }); }
		}
		incidents.push({ service: 'upi-switch', start: end - 150 * MIN, end: end - 136 * MIN });
		incidents.push({ service: 'core-banking', start: end - 27 * HOUR, end: end - 27 * HOUR + 22 * MIN });
		const parsed = [];
		for (let m = start; m < end; m += MIN) {
			const inc = incidents.find((i) => m >= i.start && m < i.end);
			const n = poisson(AVG_PER_MINUTE * trafficWeight(m));
			const stamps = [];
			for (let i = 0; i < n; i++) stamps.push(m + int(60000));
			stamps.sort((a, b) => a - b);
			for (const ts of stamps) {
				if (ts > end) break;
				const g = generate(ts, inc ? inc.service : null);
				parsed.push(...parseText(g.raw, g.service));
			}
		}
		save(parsed, false);
		// logs stored from the parser playground in this browser
		try {
			const kept = JSON.parse(localStorage.getItem('la.demo.ingested') || '[]');
			if (kept.length) save(kept, false);
		} catch (e) { /* storage unavailable */ }
	}

	function tick() {
		if (!sim.running) return;
		const now = Date.now();
		if (sim.incident && now >= sim.incident.end) sim.incident = null;
		if (!sim.incident && rnd() < INTERVAL_MS / (90 * MIN)) {
			sim.incident = { service: pick(services()), start: now, end: now + (3 + int(8)) * MIN };
		}
		const inc = sim.incident;
		const n = poisson(AVG_PER_MINUTE * trafficWeight(now) * INTERVAL_MS / MIN * (inc ? 1.3 : 1));
		const stamps = [];
		for (let i = 0; i < n; i++) stamps.push(now - int(INTERVAL_MS));
		stamps.sort((a, b) => a - b);
		const parsed = [];
		for (const ts of stamps) {
			const g = generate(ts, inc ? inc.service : null);
			parsed.push(...parseText(g.raw, g.service));
		}
		save(parsed, true);
	}

	function simStatus() {
		const s = { running: sim.running, seeding: false, intervalMs: INTERVAL_MS, avgPerMinute: AVG_PER_MINUTE, services: services() };
		const inc = sim.incident;
		if (inc && Date.now() < inc.end) {
			s.incidentService = inc.service;
			s.incidentEndsInSec = Math.floor((inc.end - Date.now()) / 1000);
		}
		return s;
	}

	// =====================================================================
	// DASHBOARD (port of DashboardService)
	// =====================================================================
	const RANGES = { '15m': [15 * MIN, 1], '1h': [HOUR, 5], '6h': [6 * HOUR, 15], '24h': [24 * HOUR, 60], '7d': [7 * 24 * HOUR, 360], all: [3650 * 24 * HOUR, 1440] };
	const rangeOf = (code) => (code && RANGES[code] ? code : '24h');
	const inRange = (from, to) => store.records.filter((r) => r.timestamp >= from && r.timestamp < to);

	function totals(list) {
		const t = { total: list.length, trace: 0, debug: 0, info: 0, warn: 0, error: 0, fatal: 0 };
		for (const r of list) t[r.level.toLowerCase()]++;
		return t;
	}

	function summary(rangeCode) {
		const range = rangeOf(rangeCode), [dur, bucket] = RANGES[range];
		const now = Date.now(), to = now + MIN;
		let from = now - dur;
		if (range === 'all') from = store.records.length ? Math.min(...store.records.map((r) => r.timestamp)) : now - 7 * 24 * HOUR;
		const list = inRange(from, to);
		const tot = totals(list);
		const errs = tot.error + tot.fatal;
		const errorRate = tot.total ? errs * 100 / tot.total : 0, warnRate = tot.total ? tot.warn * 100 / tot.total : 0;
		const health = tot.total ? Math.max(0, Math.min(100, 100 - (errorRate * 5 + warnRate))) : 100;
		const hours = Math.max(1 / 60, (now - from) / HOUR);

		// trend buckets aligned to the viewer's local time
		const bms = bucket * MIN, off = offMin(now) * MIN;
		const idx = (t) => Math.floor((t + off) / bms);
		const byB = new Map();
		for (const r of list) {
			const b = idx(r.timestamp), v = byB.get(b) || [0, 0, 0];
			v[0]++; if (r.levelRank === 3) v[1]++; if (r.levelRank >= 4) v[2]++;
			byB.set(b, v);
		}
		const trend = [];
		for (let b = idx(from); b <= idx(now); b++) { const v = byB.get(b) || [0, 0, 0]; trend.push({ t: b * bms - off, total: v[0], warnings: v[1], errors: v[2] }); }

		// error categories
		const cats = new Map();
		for (const r of list) {
			if (r.levelRank < 4) continue;
			const name = r.category || 'Other Errors';
			const c = cats.get(name) || { count: 0, lastSeen: 0, svc: new Map() };
			c.count++; c.lastSeen = Math.max(c.lastSeen, r.timestamp); c.svc.set(r.service, (c.svc.get(r.service) || 0) + 1);
			cats.set(name, c);
		}
		const categories = [...cats.entries()].map(([name, c]) => {
			const perHour = c.count / hours;
			return {
				name, count: c.count, share: errs ? round2(c.count * 100 / errs) : 0,
				severity: perHour >= 30 ? 'CRITICAL' : perHour >= 10 ? 'HIGH' : perHour >= 3 ? 'MEDIUM' : 'LOW', lastSeen: c.lastSeen,
				services: [...c.svc.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).map((e) => e[0])
			};
		}).sort((a, b) => b.count - a.count);

		// services
		const svc = new Map();
		for (const r of list) {
			const s = svc.get(r.service) || { total: 0, warnings: 0, errors: 0, lastSeen: 0 };
			s.total++; if (r.levelRank === 3) s.warnings++; if (r.levelRank >= 4) s.errors++; s.lastSeen = Math.max(s.lastSeen, r.timestamp);
			svc.set(r.service, s);
		}
		const servicesStats = [...svc.entries()].map(([name, s]) => {
			const rate = s.total ? s.errors * 100 / s.total : 0;
			return { name, total: s.total, warnings: s.warnings, errors: s.errors, errorRate: round2(rate), status: rate >= 10 ? 'CRITICAL' : rate >= 3 ? 'DEGRADED' : 'HEALTHY', lastSeen: s.lastSeen };
		}).sort((a, b) => b.errorRate - a.errorRate);

		const fm = new Map();
		for (const r of list) fm.set(r.format, (fm.get(r.format) || 0) + 1);
		const formats = [...fm.entries()].sort((a, b) => b[1] - a[1]).map(([f, c]) => ({ format: f, label: D.FORMAT_LABELS[f] || f, count: c }));

		const latest = store.records.length ? store.records.reduce((m, r) => Math.max(m, r.timestamp), 0) : null;
		return {
			range, bucketMinutes: bucket, from, to: now, totals: tot, previous: range === 'all' ? null : totals(inRange(from - dur, from)),
			healthScore: round2(health), errorRate: round2(errorRate), warnRate: round2(warnRate), trend, categories,
			services: servicesStats, formats, lastEventAt: latest, generatedAt: Date.now(), simulator: simStatus()
		};
	}

	// =====================================================================
	// LOG SEARCH (port of LogQueryService)
	// =====================================================================
	const SORTABLE = { timestamp: 'timestamp', level: 'levelRank', service: 'service', category: 'category', message: 'message', host: 'host', format: 'format', traceId: 'traceId', id: 'id' };

	function query(params) {
		const range = params.get('range') || '24h';
		let list = store.records;
		if (range !== 'all') { const from = Date.now() - RANGES[rangeOf(range)][0]; list = list.filter((r) => r.timestamp >= from); }
		const levels = (params.get('levels') || '').split(',').map((l) => l.trim().toUpperCase()).filter(Boolean);
		if (levels.length) list = list.filter((r) => levels.includes(r.level));
		const service = (params.get('service') || '').trim(); if (service) list = list.filter((r) => r.service === service);
		const category = (params.get('category') || '').trim(); if (category) list = list.filter((r) => r.category === category);
		const q = (params.get('q') || '').trim().toLowerCase();
		if (q) {
			const terms = q.split(/\s+/);
			list = list.filter((r) => terms.every((t) => [r.message, r.service, r.traceId, r.host, r.logger, r.clientIp, r.category, r.level]
				.some((v) => v && v.toLowerCase().includes(t))));
		}
		const sortKey = params.get('sort') || 'timestamp';
		const prop = SORTABLE[sortKey];
		if (!prop) { const e = new Error('Unsupported sort field: ' + sortKey); e.status = 400; throw e; }
		const dir = (params.get('dir') || 'desc').toLowerCase() === 'asc' ? 1 : -1;
		const cmp = (a, b) => (a == null ? (b == null ? 0 : -1) : b == null ? 1 : typeof a === 'number' ? a - b : String(a).localeCompare(String(b)));
		return list.slice().sort((a, b) => (cmp(a[prop], b[prop]) || a.id - b.id) * dir);
	}

	function page(params) {
		const all = query(params);
		const size = Math.max(1, Math.min(Number(params.get('size')) || 25, 200)), pg = Math.max(0, Number(params.get('page')) || 0);
		return { content: all.slice(pg * size, pg * size + size).map(dto), page: pg, size, totalElements: all.length, totalPages: Math.ceil(all.length / size) };
	}

	function csv(params) {
		const cell = (v) => {
			if (v == null) return '';
			let s = String(v).replace(/\r/g, ' ').replace(/\n/g, ' ⏎ ');
			if (s && '=+-@'.includes(s[0])) s = "'" + s;
			return '"' + s.replace(/"/g, '""') + '"';
		};
		const rows = query(params).slice(0, 10000).map(dto);
		return 'timestamp,level,service,host,category,trace_id,client_ip,message\n'
			+ rows.map((r) => [r.timestampIso, r.level, r.service, r.host, r.category, r.traceId, r.clientIp, r.message].map(cell).join(',')).join('\n') + '\n';
	}

	// =====================================================================
	// NLP (port of LogNlpService)
	// =====================================================================
	const MASKS = [
		[/\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b/g, '<UUID>'],
		[/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g, '<EMAIL>'],
		[/\b[a-z]\*{2,}\d*@[a-z]+\b/g, '<VPA>'],
		[/\b\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?\b/g, '<IP>'],
		[/\b[0-9]?X{6,}\d{4}\b/g, '<MASKED>'],
		[/\b(?:INR|USD|EUR|GBP)\s?[\d,]+(?:\.\d+)?\b/g, '<AMOUNT>'],
		[/\b(?!ISO8583\b)[A-Z]{2,6}\d{4,}\b/g, '<ID>'],
		[/\b(?=[0-9a-f]*\d)(?=[0-9a-f]*[a-f])[0-9a-f]{8,}\b/g, '<HEX>'],
		[/(?<!HTTP )\b\d+(?:[.,]\d+)*(ms|s|m|h|%)?\b/g, '<NUM>$1']
	];
	const ENTITIES = [
		['exception', /\b(?:[a-z]+\.)*([A-Z][A-Za-z]+(?:Exception|Error))\b/g],
		['errorCode', /\b(ORA-\d{5}|HTTP \d{3}|RespCode \w+|AH\d{5}|U\d{2}\b|code \d{2,3}|MT\d{3}|ISO ?8583)/g],
		['transaction', /\b((?:TXN|NOVA|ORD|RRN)\d{8,})\b/g],
		['reference', /\b((?:LA|APP|CLM|POL|FX|FD|SI|VK|MID|CIF|ATM)\d{4,})\b/g],
		['account', /\b(X{6,}\d{4})\b/g],
		['card', /\b(\dX{8,}\d{4})\b/g],
		['vpa', /\b([a-z]\*{2,}\d*@[a-z]+)\b/g],
		['duration', /\b(\d+ ?ms)\b/g],
		['amount', /\b((?:INR|USD) ?[\d,]+(?:\.\d+)?)\b/g],
		['table', /\b([A-Z][A-Z0-9]*_[A-Z0-9_]{2,})\b/g]
	];
	const STOP = new Set(['the', 'a', 'an', 'and', 'or', 'for', 'to', 'of', 'in', 'on', 'at', 'by', 'is', 'was', 'be', 'with', 'from', 'after', 'as', 'it', 'this', 'that', 'not', 'no', 'via', 'id', 'num', 'uuid', 'hex', 'masked', 'amount', 'ip', 'vpa', 'email', 'ms', 'while', 'during', 'into', 'than']);
	const tplCache = new Map();

	function template(message) {
		if (message == null) return '';
		const firstLine = message.split('\n')[0];
		const hit = tplCache.get(firstLine);
		if (hit !== undefined) return hit;
		let t = firstLine;
		for (const [re, rep] of MASKS) t = t.replace(re, rep);
		t = t.replace(/\s+/g, ' ').trim();
		if (tplCache.size > 50000) tplCache.clear();
		tplCache.set(firstLine, t);
		return t;
	}

	function clusters(records, limit) {
		const groups = new Map();
		for (const r of records) {
			const k = (r.service || '') + '|' + template(r.message);
			if (!groups.has(k)) groups.set(k, []);
			groups.get(k).push(r);
		}
		const sorted = [...groups.values()].sort((a, b) => b.length - a.length);
		const byT = new Map();
		for (const g of sorted) {
			const f = g[0], tpl = template(f.message);
			const min = Math.min(...g.map((r) => r.timestamp)), max = Math.max(...g.map((r) => r.timestamp));
			const latest = g.reduce((m, r) => (r.timestamp > m.timestamp ? r : m), f);
			const ex = byT.get(tpl);
			if (!ex) byT.set(tpl, { template: tpl, count: g.length, services: new Set([f.service || 'unknown']), category: f.category, level: f.level, firstSeen: min, lastSeen: max, sampleId: latest.id, sample: latest.message });
			else { ex.count += g.length; ex.services.add(f.service || 'unknown'); ex.firstSeen = Math.min(ex.firstSeen, min); ex.lastSeen = Math.max(ex.lastSeen, max); }
		}
		return [...byT.values()].sort((a, b) => b.count - a.count).slice(0, limit)
			.map((c, i) => Object.assign(c, { rank: i + 1, services: [...c.services].sort() }));
	}

	function entities(text) {
		const out = {};
		if (!text) return out;
		for (const [type, re] of ENTITIES) {
			const found = [];
			for (const m of text.matchAll(re)) { if (!found.includes(m[1])) found.push(m[1]); if (found.length >= 8) break; }
			if (found.length) out[type] = found;
		}
		return out;
	}

	function tokens(message) {
		const out = [];
		for (const m of template(message).toLowerCase().matchAll(/[a-z][a-z0-9]+/g)) {
			const w = m[0];
			if (w.length > 2 && !STOP.has(w) && !/\d/.test(w)) out.push(w);
		}
		return out;
	}
	function idf(corpus) {
		const df = new Map();
		for (const doc of corpus) for (const t of new Set(tokens(doc))) df.set(t, (df.get(t) || 0) + 1);
		const n = Math.max(1, corpus.length), out = new Map();
		df.forEach((d, t) => out.set(t, Math.log(1 + n / d)));
		return out;
	}
	function vector(message, idfMap) {
		const tf = new Map();
		for (const t of tokens(message)) tf.set(t, (tf.get(t) || 0) + 1);
		const v = new Map();
		tf.forEach((f, t) => v.set(t, f * (idfMap.get(t) ?? Math.log(2))));
		return v;
	}
	function cosine(a, b) {
		if (!a.size || !b.size) return 0;
		let dot = 0;
		a.forEach((w, t) => { const o = b.get(t); if (o != null) dot += w * o; });
		const na = Math.sqrt([...a.values()].reduce((s, x) => s + x * x, 0)), nb = Math.sqrt([...b.values()].reduce((s, x) => s + x * x, 0));
		return na && nb ? dot / (na * nb) : 0;
	}
	function keywords(corpus, n) {
		const idfMap = idf(corpus), score = new Map();
		for (const doc of corpus) vector(doc, idfMap).forEach((w, t) => score.set(t, (score.get(t) || 0) + w));
		return [...score.entries()].sort((a, b) => b[1] - a[1]).slice(0, n).map((e) => e[0]);
	}
	function similar(target, candidates, n, minScore) {
		const idfMap = idf([...candidates.map((c) => c.message), target.message]);
		const tv = vector(target.message, idfMap), targetTpl = template(target.message);
		const scored = [];
		for (const c of candidates) {
			if (c.id === target.id) continue;
			const s = cosine(tv, vector(c.message, idfMap));
			if (s >= minScore) scored.push({ record: c, score: s });
		}
		scored.sort((a, b) => b.score - a.score || b.record.timestamp - a.record.timestamp);
		const out = [], per = new Map();
		for (const s of scored) {
			const tpl = template(s.record.message), used = per.get(tpl) || 0;
			if (used >= (tpl === targetTpl ? 2 : 1)) continue;
			per.set(tpl, used + 1);
			out.push(s);
			if (out.length >= n) break;
		}
		return out;
	}

	function playMatch(text) {
		const t = (text || '').toLowerCase();
		for (const rule of D.PLAYBOOK) if (rule.anyOf.some((k) => t.includes(k))) return rule.play;
		return D.GENERIC_PLAY;
	}

	// =====================================================================
	// ERROR ANALYSIS (NLP path of AiAnalysisService)
	// =====================================================================
	const analyses = { logs: new Map(), overview: new Map() };
	const firstLine = (s) => { const l = (s || '').split('\n')[0]; return l.length > 300 ? l.slice(0, 297) + '...' : l; };
	const insight = (o) => Object.assign({ scope: null, logId: null, range: null, source: 'NLP', model: 'nlp-playbook', note: null, generatedAt: Date.now(), redactions: 0, redactionTypes: {}, latencyMs: null, usage: {}, incidents: [], cached: false }, o);

	function logContext(id) {
		const target = store.byId.get(id);
		if (!target) { const e = new Error('No log with id ' + id); e.status = 400; throw e; }
		const now = Date.now();
		const recent = store.records.filter((r) => r.timestamp >= now - 24 * HOUR && r.levelRank >= 3).sort((a, b) => b.timestamp - a.timestamp).slice(0, 5000);
		const tpl = template(target.message);
		const same = recent.filter((r) => template(r.message) === tpl);
		const hourly = new Array(24).fill(0);
		for (const r of same) { const h = Math.floor((now - r.timestamp) / HOUR); if (h >= 0 && h < 24) hourly[23 - h]++; }
		const ctx = {
			template: tpl, category: target.category, entities: entities(target.message), keywords: [...new Set(tokens(target.message))].slice(0, 8),
			occurrences24h: same.length, hourly, services: [...new Set(same.map((r) => r.service))].sort()
		};
		if (same.length) { ctx.firstSeen = same[same.length - 1].timestamp; ctx.lastSeen = same[0].timestamp; }
		ctx.similar = similar(target, recent.slice(0, 3000), 6, 0.25).map((s) => ({
			id: s.record.id, timestamp: s.record.timestamp, level: s.record.level, service: s.record.service, message: s.record.message,
			score: Math.round(s.score * 100), samePattern: template(s.record.message) === tpl
		}));
		ctx.trace = !target.traceId ? [] : store.records
			.filter((r) => r.traceId === target.traceId && r.id !== target.id && Math.abs(r.timestamp - target.timestamp) <= 30 * MIN)
			.sort((a, b) => a.timestamp - b.timestamp).slice(0, 25).map(dto);
		ctx.nearby = store.records
			.filter((r) => r.service === target.service && r.levelRank >= 3 && r.id !== target.id && Math.abs(r.timestamp - target.timestamp) <= 5 * MIN)
			.sort((a, b) => Math.abs(a.timestamp - target.timestamp) - Math.abs(b.timestamp - target.timestamp)).slice(0, 15)
			.sort((a, b) => a.timestamp - b.timestamp).map(dto);
		return ctx;
	}

	function analyzeLog(id, force) {
		const cached = analyses.logs.get(id);
		if (!force && cached && Date.now() - cached.generatedAt < 30 * MIN) return Object.assign({}, cached, { cached: true });
		const t = store.byId.get(id);
		if (!t) { const e = new Error('No log with id ' + id); e.status = 400; throw e; }
		const ctx = logContext(id);
		const play = playMatch(t.message + ' ' + t.category);
		const ev = [`Pattern "${ctx.template}" seen ${ctx.occurrences24h} times in 24h`];
		const ents = Object.entries(ctx.entities);
		if (ents.length) ev.push('Entities: {' + ents.map(([k, v]) => `${k}=[${v.join(', ')}]`).join(', ') + '}');
		if (ctx.trace.length) ev.push(`${ctx.trace.length} other events share trace ${t.traceId}`);
		if (ctx.nearby.length) ev.push(`${ctx.nearby.length} other warnings/errors from ${t.service} within 5 minutes`);
		const a = insight({
			scope: 'LOG', logId: id,
			summary: `${t.level} on ${t.service}: "${firstLine(t.message)}". This pattern occurred ${ctx.occurrences24h} times in the last 24 hours.`,
			rootCause: play.rootCause, impact: play.impact,
			severity: t.level === 'FATAL' ? 'CRITICAL' : t.level === 'WARN' ? 'LOW' : play.severity,
			confidence: play.id === 'generic' ? 30 : 55, recoverySteps: play.recovery, preventiveActions: play.prevention, evidence: ev, nlp: ctx
		});
		analyses.logs.set(id, a);
		return a;
	}

	function analyzeOverview(rangeCode, force) {
		const range = rangeOf(rangeCode);
		const cached = analyses.overview.get(range);
		if (!force && cached && Date.now() - cached.generatedAt < 5 * MIN) return Object.assign({}, cached, { cached: true });
		const from = Date.now() - RANGES[range][0];
		const errors = store.records.filter((r) => r.timestamp >= from && r.levelRank >= 4).sort((a, b) => b.timestamp - a.timestamp).slice(0, 5000);
		const s = summary(range);
		const top = clusters(errors, 15), patterns = clusters(errors, 1000).length;
		const nlpCtx = {
			errorsAnalysed: errors.length, patterns, keywords: keywords(errors.map((r) => r.message), 12),
			clusters: top.map((c) => ({ rank: c.rank, template: c.template, count: c.count, services: c.services, category: c.category, firstSeen: c.firstSeen, lastSeen: c.lastSeen, sampleId: c.sampleId }))
		};
		// one incident per failure mode
		const byPlay = new Map();
		for (const c of top) { const p = playMatch(c.template + ' ' + c.category); if (!byPlay.has(p.id)) byPlay.set(p.id, { play: p, cs: [] }); byPlay.get(p.id).cs.push(c); }
		const incidents = [], steps = [], prevention = [], totalErrors = Math.max(1, errors.length);
		const addAll = (arr, items) => items.forEach((x) => { if (!arr.includes(x)) arr.push(x); });
		let main = null;
		for (const { play, cs } of byPlay.values()) {
			if (incidents.length >= 5) break;
			const count = cs.reduce((a, c) => a + c.count, 0);
			const svcs = [...new Set(cs.flatMap((c) => c.services))].sort();
			const refs = cs.length > 1 ? ' · patterns ' + cs.slice(0, 4).map((c) => '#' + c.rank).join(', ') : '';
			incidents.push({ title: `${play.title} (${svcs.join(', ')})${refs}`, pattern: cs[0].rank, severity: count >= 50 ? 'HIGH' : count >= 15 ? 'MEDIUM' : 'LOW', rootCause: play.rootCause, recovery: play.recovery[0] });
			if (!main) { main = play; addAll(steps, play.recovery); }
			else if (count * 10 >= totalErrors) addAll(steps, [play.recovery[0]]);
			if (main === play || count * 10 >= totalErrors) addAll(prevention, play.prevention);
		}
		if (!main) main = D.GENERIC_PLAY;
		const empty = !errors.length;
		const a = insight({
			scope: 'OVERVIEW', range,
			summary: empty ? 'No errors in the selected window. All services are operating normally.'
				: `${s.totals.error + s.totals.fatal} errors (${s.errorRate.toFixed(2)}% of ${s.totals.total} events) grouped into ${patterns} patterns. Health score ${s.healthScore.toFixed(1)}/100. The largest pattern (${top[0].count} occurrences) is in ${top[0].services.join(', ')}: "${top[0].template}".`,
			rootCause: empty ? 'None' : main.rootCause, impact: empty ? 'None' : main.impact,
			severity: s.healthScore < 60 ? 'CRITICAL' : s.healthScore < 75 ? 'HIGH' : s.healthScore < 85 ? 'MEDIUM' : 'LOW',
			confidence: empty ? 90 : 50, recoverySteps: steps.slice(0, 7), preventiveActions: prevention.slice(0, 5),
			evidence: top.slice(0, 4).map((c) => `Pattern #${c.rank}: ${c.count}× in ${c.services.join(', ')}, last at ${fmtZ(c.lastSeen)}`),
			incidents, nlp: nlpCtx
		});
		analyses.overview.set(range, a);
		return a;
	}

	// =====================================================================
	// HTTP LAYER: fetch() and EventSource stand-ins
	// =====================================================================
	const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

	function checkBody(body) {
		if (!body || !String(body).trim()) { const e = new Error('Request body is empty – send raw log text.'); e.status = 400; throw e; }
		if (String(body).length > 5000000) { const e = new Error('Payload too large (max 5 MB per request).'); e.status = 400; throw e; }
	}
	const formatCounts = (dtos) => {
		const m = {};
		dtos.forEach((d) => { const k = d.formatLabel || '?'; m[k] = (m[k] || 0) + 1; });
		return Object.fromEntries(Object.entries(m).sort((a, b) => a[0].localeCompare(b[0])));
	};

	async function route(method, path, params, body) {
		await ready; // data is generated once the loading screen is showing
		let m;
		if (method === 'GET' && path === '/api/dashboard') return json(summary(params.get('range')));
		if (method === 'GET' && path === '/api/logs') return json(page(params));
		if (method === 'GET' && path === '/api/logs/facets') {
			return json({ levels: ['FATAL', 'ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE'], services: [...new Set(store.records.map((r) => r.service).filter(Boolean))].sort(), categories: [...new Set(store.records.map((r) => r.category).filter(Boolean))].sort() });
		}
		if (method === 'GET' && path === '/api/logs/export') return new Response(csv(params), { headers: { 'Content-Type': 'text/csv' } });
		if (method === 'GET' && (m = path.match(/^\/api\/logs\/(\d+)$/))) {
			const r = store.byId.get(Number(m[1]));
			return r ? json(dto(r)) : json({ error: 'No log with id ' + m[1] }, 400);
		}
		if (method === 'POST' && (path === '/api/logs/parse' || path === '/api/logs/ingest')) {
			checkBody(body);
			const parsed = parseText(String(body), blank(params.get('service')));
			if (path.endsWith('/ingest')) {
				const saved = save(parsed, true).map(dto);
				try {
					const kept = JSON.parse(localStorage.getItem('la.demo.ingested') || '[]').concat(parsed).slice(-500);
					localStorage.setItem('la.demo.ingested', JSON.stringify(kept));
				} catch (e) { /* storage unavailable */ }
				return json({ events: saved.length, stored: true, formats: formatCounts(saved), preview: saved.slice(0, 50) });
			}
			const previews = parsed.map((p) => dto(toRecord(p, true))); // not stored
			return json({ events: previews.length, stored: false, formats: formatCounts(previews), preview: previews.slice(0, 200) });
		}
		if (path === '/api/simulator') return json(simStatus());
		if (method === 'POST' && path === '/api/simulator/pause') { sim.running = false; return json(simStatus()); }
		if (method === 'POST' && path === '/api/simulator/resume') { sim.running = true; return json(simStatus()); }
		if (method === 'POST' && path === '/api/simulator/incident') {
			const req = blank(params.get('service')), minutes = Math.max(1, Math.min(Number(params.get('minutes')) || 5, 30));
			const svc = req && services().includes(req) ? req : pick(BUSY);
			sim.incident = { service: svc, start: Date.now(), end: Date.now() + minutes * MIN };
			return json(simStatus());
		}
		if (path === '/api/ai/status') return json({ provider: 'Google Gemini', enabled: false, configured: false, model: 'nlp-playbook', mode: 'NLP', redaction: true, maxCallsPerHour: 0 });
		if (method === 'POST' && path === '/api/ai/overview') { return json(analyzeOverview(params.get('range'), params.get('force') === 'true')); }
		if (method === 'GET' && path === '/api/ai/overview/latest') {
			const a = analyses.overview.get(rangeOf(params.get('range')));
			return a ? json(Object.assign({}, a, { cached: true })) : new Response(null, { status: 204 });
		}
		if (method === 'GET' && (m = path.match(/^\/api\/ai\/logs\/(\d+)\/context$/))) return json(logContext(Number(m[1])));
		if (method === 'GET' && (m = path.match(/^\/api\/ai\/logs\/(\d+)\/latest$/))) {
			const a = analyses.logs.get(Number(m[1]));
			return a ? json(Object.assign({}, a, { cached: true })) : new Response(null, { status: 204 });
		}
		if (method === 'POST' && (m = path.match(/^\/api\/ai\/logs\/(\d+)$/))) { return json(analyzeLog(Number(m[1]), params.get('force') === 'true')); }
		return json({ error: 'Not found: ' + method + ' ' + path }, 404);
	}

	const realFetch = window.fetch.bind(window);
	window.fetch = async function (input, init) {
		init = init || {};
		const url = new URL(typeof input === 'string' ? input : input.url, location.href);
		const i = url.pathname.indexOf('/api/');
		if (url.origin !== location.origin || i < 0) return realFetch(input, init);
		const method = (init.method || (input instanceof Request ? input.method : 'GET')).toUpperCase();
		try {
			return await route(method, url.pathname.slice(i), url.searchParams, init.body);
		} catch (e) {
			return json({ error: e.message }, e.status || 500);
		}
	};

	const RealEventSource = window.EventSource;
	class DemoEventSource {
		constructor(url) {
			this.url = url;
			this.readyState = 0;
			this.handlers = {};
			this.onLogs = (dtos) => this.emit('logs', { data: JSON.stringify(dtos) });
			setTimeout(() => {
				if (this.readyState === 2) return;
				this.readyState = 1;
				store.listeners.add(this.onLogs);
				this.emit('open', {});
			}, 50);
		}
		addEventListener(type, fn) { (this.handlers[type] = this.handlers[type] || []).push(fn); }
		removeEventListener(type, fn) { this.handlers[type] = (this.handlers[type] || []).filter((f) => f !== fn); }
		emit(type, ev) { (this.handlers[type] || []).forEach((fn) => fn(Object.assign({ type }, ev))); if (typeof this['on' + type] === 'function') this['on' + type](ev); }
		close() { this.readyState = 2; store.listeners.delete(this.onLogs); }
	}
	DemoEventSource.CONNECTING = 0; DemoEventSource.OPEN = 1; DemoEventSource.CLOSED = 2;
	window.EventSource = function (url, opts) {
		return String(url).includes('/api/logs/stream') ? new DemoEventSource(url) : new RealEventSource(url, opts);
	};
	Object.assign(window.EventSource, { CONNECTING: 0, OPEN: 1, CLOSED: 2 });

	// The CSV link points at /api/logs/export: build the file in the browser instead.
	document.addEventListener('click', (ev) => {
		const a = ev.target.closest && ev.target.closest('a[href*="/api/logs/export"]');
		if (!a) return;
		ev.preventDefault();
		const url = new URL(a.getAttribute('href'), location.href);
		const blob = new Blob([csv(url.searchParams)], { type: 'text/csv' });
		const link = document.createElement('a');
		const d = new Date();
		link.href = URL.createObjectURL(blob);
		link.download = `logs-${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}.csv`;
		document.body.appendChild(link);
		link.click();
		setTimeout(() => { URL.revokeObjectURL(link.href); link.remove(); }, 1000);
	}, true);

	// =====================================================================
	// BOOT
	// =====================================================================
	let markReady;
	const ready = new Promise((r) => { markReady = r; });
	window.LA_DEMO = { store, parseText, parseLine, template, summary, seededMs: null };
	document.addEventListener('DOMContentLoaded', () => {
		const text = document.getElementById('loaderText'), bar = document.getElementById('loaderBar');
		if (text) text.textContent = 'Generating 2 days of simulated bank logs…';
		if (bar) bar.style.width = '35%';
		setTimeout(() => { // let the loading screen paint first
			const t0 = performance.now();
			seed();
			window.LA_DEMO.seededMs = Math.round(performance.now() - t0);
			setInterval(tick, INTERVAL_MS);
			markReady();
		}, 80);
	});
})();
