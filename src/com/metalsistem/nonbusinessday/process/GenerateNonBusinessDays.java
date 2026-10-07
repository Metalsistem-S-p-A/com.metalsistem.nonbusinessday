package com.metalsistem.nonbusinessday.process;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.compiere.model.MClientInfo;
import org.compiere.model.MCountry;
import org.compiere.model.MSysConfig;
import org.compiere.model.X_C_NonBusinessDay;
import org.compiere.process.ProcessInfoParameter;
import org.compiere.process.SvrProcess;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.zkoss.json.JSONValue;

/**
 * Generates the non business days of a target year, scoped to one org so each
 * run is self contained:
 * <ul>
 * <li>the 7 weekday flags are materialized for the whole year (country agnostic);</li>
 * <li>the public holidays of the selected country are fetched from a REST API
 * (URL and JSON extraction paths are configurable via SysConfig, so any holiday
 * API can be used) and materialized stamping C_Country_ID.</li>
 * </ul>
 * Idempotent (existing rows for the same scope and date are skipped), then rows
 * of that org older than the retention (default 2 years) are removed.
 *
 * SysConfig keys (client level):
 * <ul>
 * <li>LIT_NBD_API_URL  - URL template, placeholders {COUNTRY} {YEAR} {LANG} {LANGUP} {APIKEY}</li>
 * <li>LIT_NBD_API_KEY  - optional api key substituted into {APIKEY}</li>
 * <li>LIT_NBD_JSON_LIST- dotted path to the holidays array (empty = response is the array)</li>
 * <li>LIT_NBD_JSON_DATE- dotted path to the ISO date inside each item (e.g. date.iso, startDate)</li>
 * <li>LIT_NBD_JSON_NAME- dotted path to the name inside each item (e.g. name, name[0].text)</li>
 * <li>LIT_NBD_JSON_FILTER - optional "path=value", keep only items whose path equals value (e.g. nationwide=true)</li>
 * </ul>
 */
public class GenerateNonBusinessDays extends SvrProcess {

	private int p_Year = 0;
	private int p_AD_Org_ID = -1;
	private int p_C_Calendar_ID = 0;
	private int p_C_Country_ID = 0;
	private int p_YearsToKeep = 2;
	private final boolean[] p_weekday = new boolean[8]; // index 1..7 = Mon..Sun

	private static final String[] WEEKDAY_NAME = { "", "Lunedi", "Martedi", "Mercoledi", "Giovedi", "Venerdi",
			"Sabato", "Domenica" };

	@Override
	protected void prepare() {
		for (ProcessInfoParameter p : getParameter()) {
			String n = p.getParameterName();
			switch (n) {
			case "CalendarYear": p_Year = p.getParameterAsInt(); break;
			case "AD_Org_ID": p_AD_Org_ID = p.getParameterAsInt(); break;
			case "C_Calendar_ID": p_C_Calendar_ID = p.getParameterAsInt(); break;
			case "C_Country_ID": p_C_Country_ID = p.getParameterAsInt(); break;
			case "LIT_YearsToKeep": p_YearsToKeep = p.getParameterAsInt(); break;
			case "OnMonday": p_weekday[1] = p.getParameterAsBoolean(); break;
			case "OnTuesday": p_weekday[2] = p.getParameterAsBoolean(); break;
			case "OnWednesday": p_weekday[3] = p.getParameterAsBoolean(); break;
			case "OnThursday": p_weekday[4] = p.getParameterAsBoolean(); break;
			case "OnFriday": p_weekday[5] = p.getParameterAsBoolean(); break;
			case "OnSaturday": p_weekday[6] = p.getParameterAsBoolean(); break;
			case "OnSunday": p_weekday[7] = p.getParameterAsBoolean(); break;
			default: break;
			}
		}
		if (p_Year <= 0)
			p_Year = LocalDate.now().getYear() + 1;
		if (p_AD_Org_ID < 0)
			p_AD_Org_ID = Env.getAD_Org_ID(Env.getCtx());
		if (p_C_Country_ID <= 0) {
			MCountry def = MCountry.getDefault();
			if (def != null)
				p_C_Country_ID = def.getC_Country_ID();
		}
		if (p_YearsToKeep <= 0)
			p_YearsToKeep = 2;
	}

	@Override
	protected String doIt() throws Exception {
		int clientId = Env.getAD_Client_ID(getCtx());
		if (p_C_Calendar_ID <= 0)
			p_C_Calendar_ID = MClientInfo.get(getCtx()).getC_Calendar_ID();

		int weekly = generateWeekdays(clientId);
		int holidays = generateHolidays(clientId);
		int deleted = applyRetention(clientId);
		return "Anno " + p_Year + " org " + p_AD_Org_ID + ": settimanali creati " + weekly
				+ ", festivita create " + holidays + ", eliminati oltre retention " + deleted;
	}

	private int generateWeekdays(int clientId) {
		boolean any = false;
		for (int i = 1; i <= 7; i++)
			any = any || p_weekday[i];
		if (!any)
			return 0;
		int created = 0;
		LocalDate d = LocalDate.of(p_Year, 1, 1);
		LocalDate end = LocalDate.of(p_Year, 12, 31);
		while (!d.isAfter(end)) {
			int dow = d.getDayOfWeek().getValue();
			if (p_weekday[dow]) {
				Timestamp ts = Timestamp.valueOf(d.atStartOfDay());
				if (!exists(clientId, p_AD_Org_ID, p_C_Calendar_ID, ts)) {
					insert(p_AD_Org_ID, p_C_Calendar_ID, 0, WEEKDAY_NAME[dow], ts);
					created++;
				}
			}
			d = d.plusDays(1);
		}
		return created;
	}

	private int generateHolidays(int clientId) {
		if (p_C_Country_ID <= 0)
			return 0;
		MCountry country = MCountry.get(getCtx(), p_C_Country_ID);
		String code = country != null ? country.getCountryCode() : null;
		if (code == null || code.isEmpty())
			return 0;

		String url = MSysConfig.getValue("LIT_NBD_API_URL", "", clientId);
		if (url == null || url.trim().isEmpty()) {
			addLog("LIT_NBD_API_URL not configured: public holidays skipped");
			return 0;
		}
		String apiKey = MSysConfig.getValue("LIT_NBD_API_KEY", "", clientId);
		String listPath = MSysConfig.getValue("LIT_NBD_JSON_LIST", "", clientId);
		String datePath = MSysConfig.getValue("LIT_NBD_JSON_DATE", "", clientId);
		String namePath = MSysConfig.getValue("LIT_NBD_JSON_NAME", "", clientId);
		String filter = MSysConfig.getValue("LIT_NBD_JSON_FILTER", "", clientId);

		String lang = Env.getAD_Language(getCtx());
		String lang2 = (lang != null && lang.length() >= 2) ? lang.substring(0, 2) : "en";
		url = url.replace("{COUNTRY}", code)
				.replace("{YEAR}", String.valueOf(p_Year))
				.replace("{LANG}", lang2.toLowerCase())
				.replace("{LANGUP}", lang2.toUpperCase())
				.replace("{APIKEY}", apiKey == null ? "" : apiKey);

		String body = httpGet(url);
		if (body == null)
			return 0;

		Object listObj = jsonPath(JSONValue.parse(body), listPath);
		if (!(listObj instanceof List)) {
			addLog("Holiday API: array not found at path '" + listPath + "'");
			return 0;
		}

		String filterPath = null, filterValue = null;
		if (filter != null && filter.contains("=")) {
			int i = filter.indexOf('=');
			filterPath = filter.substring(0, i).trim();
			filterValue = filter.substring(i + 1).trim();
		}

		int created = 0;
		for (Object item : (List<?>) listObj) {
			if (filterPath != null) {
				Object fv = jsonPath(item, filterPath);
				if (fv == null || !filterValue.equalsIgnoreCase(String.valueOf(fv)))
					continue;
			}
			Object dateObj = jsonPath(item, datePath);
			if (dateObj == null)
				continue;
			LocalDate d;
			try {
				d = LocalDate.parse(String.valueOf(dateObj).substring(0, 10));
			} catch (Exception ex) {
				continue;
			}
			Object nameObj = jsonPath(item, namePath);
			String name = nameObj != null ? String.valueOf(nameObj) : code + " " + d;
			Timestamp ts = Timestamp.valueOf(d.atStartOfDay());
			if (!exists(clientId, p_AD_Org_ID, p_C_Calendar_ID, ts)) {
				insert(p_AD_Org_ID, p_C_Calendar_ID, p_C_Country_ID, name, ts);
				created++;
			}
		}
		return created;
	}

	/** GET the url and return the body, or null (logged) on any error or non 200. */
	private String httpGet(String url) {
		try {
			HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
			HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("accept", "application/json")
					.timeout(Duration.ofSeconds(30)).GET().build();
			HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != 200) {
				addLog("Holiday API HTTP " + resp.statusCode());
				return null;
			}
			return resp.body();
		} catch (Exception ex) {
			addLog("Holiday API error: " + ex.getMessage());
			return null;
		}
	}

	/**
	 * Navigate a parsed JSON node (Map/List from org.zkoss.json) by a dotted path.
	 * Supports object keys and array indexes, e.g. "response.holidays",
	 * "date.iso", "startDate", "name[0].text". Empty path returns the node itself.
	 */
	private static Object jsonPath(Object node, String path) {
		if (path == null || path.trim().isEmpty())
			return node;
		for (String segment : path.trim().split("\\.")) {
			if (node == null)
				return null;
			int bracket = segment.indexOf('[');
			String key = bracket >= 0 ? segment.substring(0, bracket) : segment;
			if (!key.isEmpty()) {
				if (!(node instanceof Map))
					return null;
				node = ((Map<?, ?>) node).get(key);
			}
			while (bracket >= 0) {
				int endBracket = segment.indexOf(']', bracket);
				if (endBracket < 0 || !(node instanceof List))
					return null;
				int idx;
				try {
					idx = Integer.parseInt(segment.substring(bracket + 1, endBracket).trim());
				} catch (NumberFormatException ex) {
					return null;
				}
				List<?> list = (List<?>) node;
				if (idx < 0 || idx >= list.size())
					return null;
				node = list.get(idx);
				bracket = segment.indexOf('[', endBracket);
			}
		}
		return node;
	}

	private int applyRetention(int clientId) {
		Timestamp cutoff = Timestamp.valueOf(LocalDate.of(p_Year - p_YearsToKeep + 1, 1, 1).atStartOfDay());
		return DB.executeUpdateEx("DELETE FROM C_NonBusinessDay WHERE AD_Client_ID=? AND AD_Org_ID=? AND Date1 < ?",
				new Object[] { clientId, p_AD_Org_ID, cutoff }, get_TrxName());
	}

	private boolean exists(int clientId, int orgId, int calId, Timestamp date) {
		return DB.getSQLValueEx(get_TrxName(),
				"SELECT COUNT(*) FROM C_NonBusinessDay WHERE AD_Client_ID=? AND AD_Org_ID=?"
						+ " AND COALESCE(C_Calendar_ID,0)=? AND trunc(Date1)=?",
				clientId, orgId, calId <= 0 ? 0 : calId, date) > 0;
	}

	private void insert(int orgId, int calId, int countryId, String name, Timestamp date) {
		X_C_NonBusinessDay nbd = new X_C_NonBusinessDay(getCtx(), 0, get_TrxName());
		nbd.setAD_Org_ID(orgId);
		if (calId > 0)
			nbd.setC_Calendar_ID(calId);
		if (countryId > 0)
			nbd.setC_Country_ID(countryId);
		nbd.setName(name);
		nbd.setDate1(date);
		nbd.saveEx();
	}
}
