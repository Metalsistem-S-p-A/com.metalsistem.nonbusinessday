package com.metalsistem.nonbusinessday.process;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.Year;
import java.util.Locale;

import org.compiere.model.MClientInfo;
import org.compiere.model.MCountry;
import org.compiere.model.X_C_NonBusinessDay;
import org.compiere.process.ProcessInfoParameter;
import org.compiere.process.SvrProcess;
import org.compiere.util.DB;
import org.compiere.util.Env;

import de.focus_shift.jollyday.core.Holiday;
import de.focus_shift.jollyday.core.HolidayManager;
import de.focus_shift.jollyday.core.ManagerParameters;

/**
 * Generates the non business days of a target year, scoped to one org so each
 * run is self contained (national days under org *, plant days under the plant
 * org):
 * <ul>
 * <li>the 7 weekday flags are materialized for the whole year (country agnostic);</li>
 * <li>the public holidays of the selected country are computed with Jollyday and
 * materialized stamping C_Country_ID.</li>
 * </ul>
 * Idempotent (existing rows for the same scope and date are skipped), then rows
 * of that org older than the retention (default 2 years) are removed. No
 * parameter is persisted.
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
		Locale locale = Env.getLanguage(getCtx()).getLocale();
		int created = 0;
		ClassLoader previous = Thread.currentThread().getContextClassLoader();
		try {
			Thread.currentThread().setContextClassLoader(HolidayManager.class.getClassLoader());
			HolidayManager manager = HolidayManager.getInstance(ManagerParameters.create(code.toLowerCase(), null));
			for (Holiday h : manager.getHolidays(Year.of(p_Year))) {
				Timestamp ts = Timestamp.valueOf(h.getDate().atStartOfDay());
				if (!exists(clientId, p_AD_Org_ID, p_C_Calendar_ID, ts)) {
					insert(p_AD_Org_ID, p_C_Calendar_ID, p_C_Country_ID, h.getDescription(locale), ts);
					created++;
				}
			}
		} 
		catch(Exception ex) {
			addLog(ex.getMessage());
			ex.printStackTrace();
		}
		finally {
			Thread.currentThread().setContextClassLoader(previous);
		}
		return created;
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
