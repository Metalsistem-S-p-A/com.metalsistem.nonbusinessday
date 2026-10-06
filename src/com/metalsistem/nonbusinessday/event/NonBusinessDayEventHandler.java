package com.metalsistem.nonbusinessday.event;

import java.sql.Timestamp;

import org.adempiere.base.event.AbstractEventHandler;
import org.adempiere.base.event.IEventTopics;
import org.compiere.model.MInvoice;
import org.compiere.model.MInvoicePaySchedule;
import org.compiere.model.MOrder;
import org.compiere.model.PO;
import org.compiere.util.DB;
import org.osgi.service.event.Event;

import com.metalsistem.nonbusinessday.util.BusinessDay;

/**
 * Auto-registered event handler (DS, bound to IEventManager) that shifts the
 * invoice pay schedule due dates to the next business day when the payment term
 * has IsNextBusinessDay set, reading C_NonBusinessDay scoped by the invoice org.
 *
 * The pay schedule is built by MInvoice during prepare/complete, so the handler
 * runs on DOC_AFTER_PREPARE and DOC_AFTER_COMPLETE, when the C_InvoicePaySchedule
 * rows already exist, and re-shifts them. nextBusinessDay is idempotent, so a
 * complete that re-prepares the schedule ends up with the same shifted dates.
 */
public class NonBusinessDayEventHandler extends AbstractEventHandler {

	@Override
	protected void initialize() {
		registerTableEvent(IEventTopics.DOC_AFTER_PREPARE, MInvoice.Table_Name);
		registerTableEvent(IEventTopics.DOC_AFTER_COMPLETE, MInvoice.Table_Name);
		registerTableEvent(IEventTopics.DOC_AFTER_PREPARE, MOrder.Table_Name);
		registerTableEvent(IEventTopics.DOC_AFTER_COMPLETE, MOrder.Table_Name);
	}

	@Override
	protected void doHandleEvent(Event event) {
		PO po = getPO(event);
		if (po instanceof MInvoice) {
			MInvoice invoice = (MInvoice) po;
			fixInvoicePaySchedule(invoice);		
		}
		else if(po instanceof MOrder) {
			MOrder order = (MOrder) po;
			fixOrderDates(order);
		}
	}
	
	private void fixOrderDates(MOrder order) {
		Timestamp shifted = BusinessDay.nextBusinessDay(order.getDatePromised(), order.getAD_Client_ID(), order.getAD_Org_ID(), order.get_TrxName());
		if (shifted != null && shifted.compareTo(order.getDatePromised()) != 0) {
			order.setDatePromised(shifted);
			order.saveEx();
		}
	}

	private void fixInvoicePaySchedule(MInvoice invoice) {
		if (invoice.getC_PaymentTerm_ID() <= 0)
			return;

		boolean nextBusinessDay = "Y".equals(DB.getSQLValueStringEx(invoice.get_TrxName(),
				"SELECT IsNextBusinessDay FROM C_PaymentTerm WHERE C_PaymentTerm_ID=?",
				invoice.getC_PaymentTerm_ID()));
		if (!nextBusinessDay)
			return;

		MInvoicePaySchedule[] schedules = MInvoicePaySchedule.getInvoicePaySchedule(invoice.getCtx(),
				invoice.getC_Invoice_ID(), 0, invoice.get_TrxName());
		for (MInvoicePaySchedule ips : schedules) {
			Timestamp due = ips.getDueDate();
			if (due == null)
				continue;
			Timestamp shifted = BusinessDay.nextBusinessDay(due, invoice.getAD_Client_ID(), invoice.getAD_Org_ID(),
					invoice.get_TrxName());
			if (shifted != null && shifted.compareTo(due) != 0) {
				ips.setDueDate(shifted);
				ips.saveEx();
			}
		}
	}
}
