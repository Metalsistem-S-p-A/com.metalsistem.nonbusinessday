package com.metalsistem.nonbusinessday.util;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.Set;

import org.adempiere.exceptions.DBException;
import org.compiere.util.DB;
import org.compiere.util.TimeUtil;

/**
 * Business day helper based on C_NonBusinessDay.
 *
 * Scope: client + org (specific or shared org *) + country (specific or
 * country agnostic 0). A day is a non business day when it is Saturday/Sunday
 * or when a matching C_NonBusinessDay row exists.
 *
 * isHoliday / getHolidays return C_NonBusinessDay matches only (weekend not
 * considered), for callers that handle the weekend on their own (e.g. the
 * attendance extra hours). isNonBusinessDay / nextBusinessDay also treat the
 * weekend as non business, as the standard payment term due date shift does.
 */
public final class BusinessDay {
	
	/** C_NonBusinessDay match only (weekend NOT considered). Country derived from the client. */
	public static boolean isHoliday(Timestamp date, int adClientId, int adOrgId, String trxName) {
		return isHoliday(date, adClientId, adOrgId, getClientCountryId(adClientId), trxName);
	}

	/** C_NonBusinessDay match only (weekend NOT considered). */
	public static boolean isHoliday(Timestamp date, int adClientId, int adOrgId, int adCountryId, String trxName) {
		if (date == null)
			return false;
		final String sql = "SELECT COUNT(*) FROM C_NonBusinessDay"
				+ " WHERE IsActive='Y' AND AD_Client_ID=?"
				+ " AND (AD_Org_ID=? OR AD_Org_ID=0)"
				+ " AND COALESCE(C_Country_ID,0) IN (0,?)"
				+ " AND trunc(Date1)=?";
		return DB.getSQLValueEx(trxName, sql, adClientId, adOrgId, adCountryId, TimeUtil.getDay(date)) > 0;
	}

	/** Weekend OR holiday. Country derived from the client. */
	public static boolean isNonBusinessDay(Timestamp date, int adClientId, int adOrgId, String trxName) {
		return isNonBusinessDay(date, adClientId, adOrgId, getClientCountryId(adClientId), trxName);
	}

	/** Weekend OR holiday. */
	public static boolean isNonBusinessDay(Timestamp date, int adClientId, int adOrgId, int adCountryId, String trxName) {
		if (date == null)
			return false;
		// Weekends are data too: the generator materializes the configured
		// weekdays into C_NonBusinessDay, so no weekday is hardcoded here.
		return isHoliday(date, adClientId, adOrgId, adCountryId, trxName);
	}

	/** Returns date, or the first following business day when date is non business. Country derived from the client. */
	public static Timestamp nextBusinessDay(Timestamp date, int adClientId, int adOrgId, String trxName) {
		return nextBusinessDay(date, adClientId, adOrgId, getClientCountryId(adClientId), trxName);
	}

	/** Returns date, or the first following business day when date is non business. */
	public static Timestamp nextBusinessDay(Timestamp date, int adClientId, int adOrgId, int adCountryId, String trxName) {
		if (date == null)
			return null;
		Timestamp d = TimeUtil.getDay(date);
		int guard = 0;
		while (isNonBusinessDay(d, adClientId, adOrgId, adCountryId, trxName)) {
			d = TimeUtil.addDays(d, 1);
			if (++guard > 400) // safety: never loop forever on a fully blocked range
				break;
		}
		return d;
	}

	/** Set of C_NonBusinessDay dates (truncated) in [from, to], same scope. */
	public static Set<Timestamp> getHolidays(Timestamp from, Timestamp to, int adClientId, int adOrgId, int adCountryId,
			String trxName) {
		Set<Timestamp> result = new HashSet<>();
		if (from == null || to == null)
			return result;
		final String sql = "SELECT trunc(Date1) FROM C_NonBusinessDay"
				+ " WHERE IsActive='Y' AND AD_Client_ID=?"
				+ " AND (AD_Org_ID=? OR AD_Org_ID=0)"
				+ " AND COALESCE(C_Country_ID,0) IN (0,?)"
				+ " AND trunc(Date1) BETWEEN ? AND ?";
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			pstmt = DB.prepareStatement(sql, trxName);
			DB.setParameters(pstmt,
					new Object[] { adClientId, adOrgId, adCountryId, TimeUtil.getDay(from), TimeUtil.getDay(to) });
			rs = pstmt.executeQuery();
			while (rs.next())
				result.add(rs.getTimestamp(1));
		} catch (SQLException e) {
			throw new DBException(e);
		} finally {
			DB.close(rs, pstmt);
			rs = null;
			pstmt = null;
		}
		return result;
	}

	/** Country of the client, from its language country code (as the legacy nextbusinessday function did). 0 if none. */
	public static int getClientCountryId(int adClientId) {
		final String sql = "SELECT COALESCE(MAX(co.C_Country_ID),0) FROM AD_Client cl"
				+ " JOIN AD_Language l ON cl.AD_Language=l.AD_Language"
				+ " JOIN C_Country co ON l.CountryCode=co.CountryCode"
				+ " WHERE cl.AD_Client_ID=?";
		int c = DB.getSQLValueEx(null, sql, adClientId);
		return c < 0 ? 0 : c;
	}
}
