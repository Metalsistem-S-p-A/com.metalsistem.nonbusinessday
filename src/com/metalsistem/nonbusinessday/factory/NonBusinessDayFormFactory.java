package com.metalsistem.nonbusinessday.factory;

import org.adempiere.webui.factory.IFormFactory;
import org.adempiere.webui.panel.ADForm;
import org.adempiere.webui.panel.IFormController;
import org.osgi.service.component.annotations.Component;

import com.metalsistem.nonbusinessday.form.WNonBusinessDayCalendar;

/**
 * Registers the non-business day management form (standalone bundle, hence via
 * IFormFactory DS and not with the {@code @Form} annotation, which the core only
 * discovers in org.adempiere.ui.zk fragments). The mapping is on the AD_Form
 * Classname.
 */
@Component(immediate = true, service = IFormFactory.class, property = { "service.ranking:Integer=100" })
public class NonBusinessDayFormFactory implements IFormFactory {

	@Override
	public ADForm newFormInstance(String formName) {
		if ("com.metalsistem.nonbusinessday.form.WNonBusinessDayCalendar".equals(formName)) {
			IFormController controller = new WNonBusinessDayCalendar();
			ADForm adForm = controller.getForm();
			adForm.setICustomForm(controller);
			return adForm;
		}
		return null;
	}
}
