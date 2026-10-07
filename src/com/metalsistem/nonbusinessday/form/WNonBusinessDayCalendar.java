/******************************************************************************
 * Metalsistem S.p.A.                                                         *
 * Non-business day (C_NonBusinessDay) management form - calendar.             *
 *****************************************************************************/
package com.metalsistem.nonbusinessday.form;

import static org.compiere.model.SystemIDs.COLUMN_C_PERIOD_AD_ORG_ID;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Date;
import java.util.logging.Level;

import org.adempiere.exceptions.DBException;
import org.adempiere.webui.LayoutUtils;
import org.adempiere.webui.apps.ProcessModalDialog;
import org.adempiere.webui.component.Button;
import org.adempiere.webui.component.ConfirmPanel;
import org.adempiere.webui.editor.WTableDirEditor;
import org.adempiere.webui.event.DialogEvents;
import org.adempiere.webui.event.ValueChangeEvent;
import org.adempiere.webui.event.ValueChangeListener;
import org.adempiere.webui.factory.ButtonFactory;
import org.adempiere.webui.panel.ADForm;
import org.adempiere.webui.panel.CustomForm;
import org.adempiere.webui.panel.IFormController;
import org.adempiere.webui.util.ZKUpdateUtil;
import org.adempiere.webui.window.Dialog;
import org.compiere.model.MClientInfo;
import org.compiere.model.MColumn;
import org.compiere.model.MLookup;
import org.compiere.model.MLookupFactory;
import org.compiere.model.X_C_NonBusinessDay;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.DisplayType;
import org.compiere.util.Env;
import org.compiere.util.Msg;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.zkoss.json.JSONArray;
import org.zkoss.json.JSONObject;
import org.zkoss.json.JSONValue;
import org.zkoss.zk.ui.Page;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zk.ui.util.Clients;
import org.zkoss.zul.Borderlayout;
import org.zkoss.zul.Center;
import org.zkoss.zul.Checkbox;
import org.zkoss.zul.Datebox;
import org.zkoss.zul.Div;
import org.zkoss.zul.Hlayout;
import org.zkoss.zul.Label;
import org.zkoss.zul.North;
import org.zkoss.zul.Space;
import org.zkoss.zul.South;
import org.zkoss.zul.Textbox;
import org.zkoss.zul.Vlayout;
import org.zkoss.zul.Window;

/**
 * Non-business day (C_NonBusinessDay) management from a calendar
 * (FullCalendar, MIT), per org/plant.
 * <ul>
 * <li>click or selection on the calendar: opens the "New holiday" dialog
 * (description, start/end date, yearly) to create one or more rows;</li>
 * <li>click on an event: opens "Edit holiday" with Save and Delete;</li>
 * <li>colored events: orange = one-off, blue = yearly recurring;</li>
 * <li>"Generate year" launches the generator process with the 7 weekday flags.</li>
 * </ul>
 * FullCalendar owns the current view and keeps it across refreshes (we reuse the
 * same instance). Registered via IFormFactory DS.
 */
public class WNonBusinessDayCalendar implements IFormController, EventListener<Event>, ValueChangeListener {

	private static final CLogger log = CLogger.getCLogger(WNonBusinessDayCalendar.class);

	private static final String PROCESS_CLASSNAME = "com.metalsistem.nonbusinessday.process.GenerateNonBusinessDays";
	private static final String EVT_SEL = "onCalSel";
	private static final String EVT_DATES = "onCalDates";
	private static final String COLOR_COMPANY = "#f39c12";
	private static final String COLOR_NATIONAL = "#2980b9";

	private final CustomForm form = new CustomForm() {
		private static final long serialVersionUID = 1L;
		private boolean rendered = false;
		@Override
		public void onPageAttached(Page newpage, Page oldpage) {
			super.onPageAttached(newpage, oldpage);
			if (newpage != null && !rendered) {
				rendered = true;
				Events.echoEvent("onInitRender", this, (String) null);
			}
		}
	};
	private final Borderlayout mainLayout = new Borderlayout();

	private WTableDirEditor orgEditor;
	private WTableDirEditor calendarEditor;
	private final Button generateBtn = new Button();
	private final Div calDiv = new Div();

	// edit dialog
	private final Window dlg = new Window();
	private final Textbox descBox = new Textbox();
	private final Datebox dateFrom = new Datebox();
	private final Datebox dateTo = new Datebox();
	private final Checkbox activeChk = new Checkbox();
	private Button saveBtn = new Button();
	private Button cancelBtn = new Button();
	private int m_editId = 0;
	private LocalDate m_viewFrom;
	private LocalDate m_viewTo;


	public WNonBusinessDayCalendar() {
		try {
			dynInit();
			zkInit();
		} catch (Exception e) {
			log.log(Level.SEVERE, "WNonBusinessDayCalendar init", e);
		}
	}

	private void dynInit() throws Exception {
		MLookup lookupOrg = MLookupFactory.get(Env.getCtx(), form.getWindowNo(), 0, COLUMN_C_PERIOD_AD_ORG_ID,
				DisplayType.TableDir);
		orgEditor = new WTableDirEditor("AD_Org_ID", true, false, true, lookupOrg);
		orgEditor.setValue(Env.getAD_Org_ID(Env.getCtx()));
		orgEditor.addValueChangeListener(this);

		int calColId = MColumn.getColumn_ID("C_NonBusinessDay", "C_Calendar_ID");
		MLookup lookupCal = MLookupFactory.get(Env.getCtx(), form.getWindowNo(), 0, calColId, DisplayType.TableDir);
		calendarEditor = new WTableDirEditor("C_Calendar_ID", true, false, true, lookupCal);
		int defCal = MClientInfo.get(Env.getCtx()).getC_Calendar_ID();
		if (defCal > 0)
			calendarEditor.setValue(defCal);
		calendarEditor.addValueChangeListener(this);
	}

	private void zkInit() throws Exception {
		Div root = new Div();
		root.setStyle("height:100%; width:100%; overflow:hidden;");
		root.appendChild(mainLayout);
		form.appendChild(root);
		form.addEventListener("onInitRender", this);
		ZKUpdateUtil.setWidth(mainLayout, "100%");
		ZKUpdateUtil.setHeight(mainLayout, "100%");

		// --- toolbar (North): org + calendar ---
		Hlayout bar = new Hlayout();
		bar.setStyle("padding:6px; align-items:center;");
		bar.appendChild(new Label(Msg.translate(Env.getCtx(), "AD_Org_ID")));
		bar.appendChild(orgEditor.getComponent());
		bar.appendChild(new Space());
		bar.appendChild(new Label(Msg.translate(Env.getCtx(), "C_Calendar_ID")));
		bar.appendChild(calendarEditor.getComponent());
		North north = new North();
		north.appendChild(bar);
		mainLayout.appendChild(north);

		// --- calendar (Center) ---
		calDiv.addEventListener(EVT_SEL, this);
		calDiv.addEventListener(EVT_DATES, this);
		calDiv.setStyle("width:100%; height:100%; padding:8px; box-sizing:border-box;");
		Center center = new Center();
		center.setAutoscroll(true);
		center.appendChild(calDiv);
		mainLayout.appendChild(center);

		// --- footer (South): hint on the left, generate year on the right ---
		Div sbar = new Div();
		sbar.setStyle("display:flex; justify-content:space-between; align-items:center; padding:6px; gap:12px;");
		Label hint = new Label(Msg.getMsg(Env.getCtx(), "LIT_NBDCalendarHint"));
		hint.setStyle("color:#666;");
		sbar.appendChild(hint);
		generateBtn.setLabel(Msg.getMsg(Env.getCtx(), "LIT_GenerateYear"));
		generateBtn.addEventListener(Events.ON_CLICK, this);
		sbar.appendChild(generateBtn);
		South south = new South();
		south.appendChild(sbar);
		mainLayout.appendChild(south);

		buildDialog();
	}

	private void buildDialog() {
		dlg.setTitle(Msg.getMsg(Env.getCtx(), "LIT_NonBusinessDay"));
		dlg.setClosable(true);
		dlg.setBorder("normal");
		dlg.setWidth("380px");
		dlg.setVisible(false);
		dlg.addEventListener(Events.ON_CLOSE, this);
		form.appendChild(dlg);

		// Same structure as AbstractProcessDialog: main -> top (fields) + bottom (buttons)
		Div main = new Div();
		main.setSclass("main-parameter-layout");
		dlg.appendChild(main);

		Vlayout top = new Vlayout();
		top.setSclass("top-parameter-layout");
		main.appendChild(top);

		top.appendChild(new Label(Msg.translate(Env.getCtx(), "Description")));
		ZKUpdateUtil.setWidth(descBox, "100%");
		top.appendChild(descBox);

		top.appendChild(new Label(Msg.getMsg(Env.getCtx(), "DateFrom")));
		dateFrom.setFormat("dd/MM/yyyy");
		top.appendChild(dateFrom);

		top.appendChild(new Label(Msg.getMsg(Env.getCtx(), "DateTo")));
		dateTo.setFormat("dd/MM/yyyy");
		top.appendChild(dateTo);

		activeChk.setLabel(Msg.translate(Env.getCtx(), "IsActive"));
		top.appendChild(activeChk);

		Vlayout bottom = new Vlayout();
		bottom.setSclass("bottom-parameter-layout");
		main.appendChild(bottom);

		Div conf = new Div();
		conf.setSclass("button-container");
		bottom.appendChild(conf);

		saveBtn = ButtonFactory.createNamedButton(ConfirmPanel.A_OK, true, true);
		saveBtn.setId("Ok");
		saveBtn.addEventListener(Events.ON_CLICK, this);
		conf.appendChild(saveBtn);
		conf.appendChild(new Space());

		cancelBtn = ButtonFactory.createNamedButton(ConfirmPanel.A_CANCEL, true, true);
		cancelBtn.setId("Cancel");
		cancelBtn.addEventListener(Events.ON_CLICK, this);
		conf.appendChild(cancelBtn);
	}

	// ------------------------------------------------------------------ events

	@Override
	public void onEvent(Event e) throws Exception {
		Object t = e.getTarget();
		String name = e.getName();
		if ("onInitRender".equals(name)) {
			initCalendar();
			return;
		}
		if (t == calDiv && EVT_DATES.equals(name)) {
			onCalDates(String.valueOf(e.getData()));
		} else if (t == generateBtn) {
			openGenerator();
		} else if (t == calDiv && EVT_SEL.equals(name)) {
			openFromCalendar(String.valueOf(e.getData()));
		} else if (t == saveBtn) {
			saveDialog();
		} else if (t == cancelBtn) {
			dlg.setVisible(false);
		} else if (t == dlg && Events.ON_CLOSE.equals(name)) {
			e.stopPropagation(); // keep the window for reuse, just hide it
			dlg.setVisible(false);
		} else if (t instanceof ProcessModalDialog && DialogEvents.ON_WINDOW_CLOSE.equals(name)) {
			refreshRange();
		}
	}

	@Override
	public void valueChange(ValueChangeEvent evt) {
		String pn = evt.getPropertyName();
		if ("AD_Org_ID".equals(pn) || "C_Calendar_ID".equals(pn))
			refreshRange();
	}

	// ------------------------------------------------------------------- logic

	private int getOrgId() {
		Object v = orgEditor.getValue();
		if (v instanceof Integer)
			return (Integer) v;
		if (v != null)
			try {
				return Integer.parseInt(v.toString());
			} catch (NumberFormatException ex) {
				return 0;
			}
		return 0;
	}

	private int getCalendarId() {
		Object v = calendarEditor.getValue();
		if (v instanceof Integer)
			return (Integer) v;
		if (v != null)
			try {
				return Integer.parseInt(v.toString());
			} catch (NumberFormatException ex) {
				return 0;
			}
		return 0;
	}

	private static Date toDate(LocalDate d) {
		return Timestamp.valueOf(d.atStartOfDay());
	}

	private static LocalDate toLocal(Date d) {
		return new Timestamp(d.getTime()).toLocalDateTime().toLocalDate();
	}

	private void openFromCalendar(String payload) {
		if (payload == null)
			return;
		String[] p = payload.split("\\|");
		if (p.length < 2)
			return;
		try {
			if ("new".equals(p[0]) && p.length >= 3) {
				LocalDate from = LocalDate.parse(p[1].substring(0, 10));
				LocalDate to = LocalDate.parse(p[2].substring(0, 10)).minusDays(1); // FC end exclusive
				if (to.isBefore(from))
					to = from;
				openDialog(from, to, 0, "", true);
			} else if ("edit".equals(p[0])) {
				int id = Integer.parseInt(p[1]);
				X_C_NonBusinessDay r = new X_C_NonBusinessDay(Env.getCtx(), id, null);
				if (r.get_ID() != id)
					return;
				LocalDate d = r.getDate1().toLocalDateTime().toLocalDate();
				openDialog(d, d, id, r.getName(), r.isActive());
			}
		} catch (Exception ex) {
			log.log(Level.WARNING, "openFromCalendar " + payload, ex);
		}
	}

	private void openDialog(LocalDate from, LocalDate to, int id, String desc, boolean active) {
		m_editId = id;
		descBox.setValue(desc);
		dateFrom.setValue(toDate(from));
		dateTo.setValue(toDate(to));
		activeChk.setChecked(active);
		activeChk.setVisible(id > 0);
		dlg.setTitle(id > 0 ? Msg.getMsg(Env.getCtx(), "LIT_EditNonBusinessDay") : Msg.getMsg(Env.getCtx(), "LIT_NewNonBusinessDay"));
		dlg.setVisible(true);
		dlg.doHighlighted();
	}

	private void saveDialog() {
		Date df = dateFrom.getValue();
		if (df == null) {
			Dialog.error(form.getWindowNo(), Msg.getMsg(Env.getCtx(), "LIT_DateFromMandatory"));
			return;
		}
		LocalDate from = toLocal(df);
		LocalDate to = dateTo.getValue() != null ? toLocal(dateTo.getValue()) : from;
		if (to.isBefore(from))
			to = from;
		if (from.plusDays(400).isBefore(to))
			return;
		String nm = descBox.getValue();
		if (nm == null || nm.trim().isEmpty())
			nm = "Chiusura";
		int orgId = getOrgId();

		if (m_editId > 0) {
			X_C_NonBusinessDay r = new X_C_NonBusinessDay(Env.getCtx(), m_editId, null);
			if (r.get_ID() == m_editId) {
				r.setName(nm);
				r.setDate1(Timestamp.valueOf(from.atStartOfDay()));
				r.setIsActive(activeChk.isChecked());
				r.saveEx();
				dlg.setVisible(false);
				if (r.isActive()) {
					JSONArray a = new JSONArray();
					a.add(buildEvent(r.get_ID(), r.getDate1(), r.getName(), r.getC_Country_ID() > 0));
					pushUpsert(a);
				} else {
					pushRemove(r.get_ID());
				}
				return;
			}
			dlg.setVisible(false);
			return;
		}

		int calId = getCalendarId();
		if (calId <= 0) {
			Dialog.error(form.getWindowNo(), Msg.getMsg(Env.getCtx(), "LIT_SelectCalendar"));
			return;
		}
		int clientId = Env.getAD_Client_ID(Env.getCtx());
		JSONArray created = new JSONArray();
		LocalDate d = from;
		while (!d.isAfter(to)) {
			Timestamp ts = Timestamp.valueOf(d.atStartOfDay());
			if (!existsNbd(clientId, orgId, calId, ts)) {
				X_C_NonBusinessDay nbd = new X_C_NonBusinessDay(Env.getCtx(), 0, null);
				nbd.setAD_Org_ID(orgId);
				nbd.setC_Calendar_ID(calId);
				nbd.setName(nm);
				nbd.setDate1(ts);
				nbd.setIsActive(activeChk.isChecked());
				nbd.saveEx();
				if (nbd.isActive())
					created.add(buildEvent(nbd.get_ID(), ts, nm, nbd.getC_Country_ID() > 0));
			}
			d = d.plusDays(1);
		}
		dlg.setVisible(false);
		pushUpsert(created);
	}

	private JSONObject buildEvent(int id, Timestamp date, String name, boolean national) {
		JSONObject ev = new JSONObject();
		ev.put("id", String.valueOf(id));
		ev.put("title", name);
		ev.put("start", date.toLocalDateTime().toLocalDate().toString());
		ev.put("allDay", Boolean.TRUE);
		ev.put("color", national ? COLOR_NATIONAL : COLOR_COMPANY);
		return ev;
	}

	private void pushUpsert(JSONArray events) {
		if (events == null || events.isEmpty())
			return;
		Clients.evalJavaScript("window.msNbd && window.msNbd.upsertAll('" + calDiv.getUuid() + "'," + events.toJSONString() + ");");
	}

	private void pushRemove(int id) {
		Clients.evalJavaScript("window.msNbd && window.msNbd.removeEvent('" + calDiv.getUuid() + "','" + id + "');");
	}


	private boolean existsNbd(int clientId, int orgId, int calId, Timestamp date) {
		return DB.getSQLValueEx(null,
				"SELECT COUNT(*) FROM C_NonBusinessDay WHERE AD_Client_ID=? AND AD_Org_ID=? AND C_Calendar_ID=?"
						+ " AND trunc(Date1)=trunc(cast(? as timestamp))",
				clientId, orgId, calId, date) > 0;
	}

	private void initCalendar() {
		String uuid = calDiv.getUuid();
		String lang = Env.getAD_Language(Env.getCtx());
		String loc = (lang != null && lang.length() >= 2) ? lang.substring(0, 2) : "en";
		Clients.evalJavaScript(buildBootstrap(uuid, loc));
	}

	private void onCalDates(String payload) {
		if (payload == null)
			return;
		String[] p = payload.split("\\|");
		if (p.length < 2)
			return;
		try {
			m_viewFrom = LocalDate.parse(p[0].substring(0, 10));
			m_viewTo = LocalDate.parse(p[1].substring(0, 10));
		} catch (Exception ex) {
			return;
		}
		loadRange(m_viewFrom, m_viewTo);
	}

	private void refreshRange() {
		Clients.evalJavaScript("window.msNbd && window.msNbd.refetch('" + calDiv.getUuid() + "');");
	}

	/** Loads only the events within the calendar's visible range and sends them. */
	private void loadRange(LocalDate from, LocalDate to) {
		int clientId = Env.getAD_Client_ID(Env.getCtx());
		int orgId = getOrgId();
		int calId = getCalendarId();

		JSONArray events = new JSONArray();
		final String sql = "SELECT C_NonBusinessDay_ID, Date1, Name, COALESCE(C_Country_ID,0)"
				+ " FROM C_NonBusinessDay WHERE IsActive='Y' AND AD_Client_ID=? AND (AD_Org_ID=? OR AD_Org_ID=0)"
				+ " AND C_Calendar_ID=? AND Date1 >= ? AND Date1 < ? ORDER BY Date1";
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			pstmt = DB.prepareStatement(sql, null);
			DB.setParameters(pstmt, new Object[] { clientId, orgId, calId,
					Timestamp.valueOf(from.atStartOfDay()), Timestamp.valueOf(to.atStartOfDay()) });
			rs = pstmt.executeQuery();
			while (rs.next()) {
				boolean national = rs.getInt(4) > 0;
				JSONObject ev = new JSONObject();
				ev.put("id", String.valueOf(rs.getInt(1)));
				ev.put("title", rs.getString(3));
				ev.put("start", rs.getTimestamp(2).toLocalDateTime().toLocalDate().toString());
				ev.put("allDay", Boolean.TRUE);
				ev.put("color", national ? COLOR_NATIONAL : COLOR_COMPANY);
				events.add(ev);
			}
		} catch (SQLException e) {
			throw new DBException(e);
		} finally {
			DB.close(rs, pstmt);
		}
		String uuid = calDiv.getUuid();
		Clients.evalJavaScript("window.msNbd && window.msNbd.deliver('" + uuid + "'," + events.toJSONString() + ");");
	}

	private void openGenerator() {
		int pid = DB.getSQLValueEx(null, "SELECT AD_Process_ID FROM AD_Process WHERE Classname=? AND IsActive='Y'",
				PROCESS_CLASSNAME);
		if (pid <= 0) {
			Dialog.error(form.getWindowNo(), Msg.getMsg(Env.getCtx(), "LIT_ProcessNotFound") + " " + PROCESS_CLASSNAME);
			return;
		}
		ProcessModalDialog dialog = new ProcessModalDialog(this, form.getWindowNo(), pid, 0, 0, false);
		if (dialog.isValid()) {
			dialog.setBorder("normal");
			form.appendChild(dialog);
			LayoutUtils.openOverlappedWindow(form, dialog, "middle_center");
			dialog.focus();
		}
	}

	// ----------------------------------------------------------- client assets

	/**
	 * Loads web/bootstrap.js as a template and substitutes its __MS_*__ tokens
	 * with the FullCalendar asset contents (CSS/JS, injected because they are not
	 * served over HTTP) and the target uuid / locale. Each value is emitted as a
	 * JSON string literal via {@link JSONValue#toJSONString}, so no custom
	 * escaping is needed.
	 */
	private String buildBootstrap(String uuid, String loc) {
		String tpl = readResource("web/bootstrap.js");
		String mainJs = readResource("web/fullcalendar/fullcalendar.global.js");
		
		String themeJs = readResource("web/fullcalendar/themes/monarch/global.js");
		String localesJs = readResource("web/fullcalendar/locales-all/global.js");
		String skeletonCss = readResource("web/fullcalendar/skeleton.css");
		String themeCss = readResource("web/fullcalendar/themes/monarch/theme.css");
		String purpleCss = readResource("web/fullcalendar/themes/monarch/palettes/purple.css");
		String customCss = readResource("web/style.css");
		return tpl
				.replace("__MS_SKELETON_CSS__", JSONValue.toJSONString(skeletonCss))
				.replace("__MS_THEME_CSS__", JSONValue.toJSONString(themeCss))
				.replace("__MS_PURPLE_CSS__", JSONValue.toJSONString(purpleCss))
				.replace("__MS_CUSTOM_CSS__", JSONValue.toJSONString(customCss))
				.replace("__MS_FC_MAIN__", JSONValue.toJSONString(mainJs))
				.replace("__MS_FC_THEME__", JSONValue.toJSONString(themeJs))
				.replace("__MS_FC_LOCALES__", JSONValue.toJSONString(localesJs))
				.replace("__MS_UUID__", JSONValue.toJSONString(uuid))
				.replace("__MS_LOCALE__", JSONValue.toJSONString(loc));
	}

	private String readResource(String path) {
		java.net.URL url = null;
		try {
			Bundle bb = FrameworkUtil.getBundle(getClass());
			if (bb != null)
				url = bb.getEntry(path);
		} catch (Throwable ignore) {
			// fall back to the classloader
		}
		if (url == null)
			url = getClass().getClassLoader().getResource(path);
		if (url == null) {
			log.warning("Resource not found in bundle: " + path);
			return null;
		}
		try (InputStream in = url.openStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (Exception ex) {
			log.log(Level.WARNING, "readResource " + path, ex);
			return null;
		}
	}
	
	@Override
	public ADForm getForm() {
		return form;
	}	
}
