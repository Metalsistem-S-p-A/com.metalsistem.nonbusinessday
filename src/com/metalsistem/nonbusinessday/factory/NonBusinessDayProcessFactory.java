package com.metalsistem.nonbusinessday.factory;

import org.adempiere.base.IProcessFactory;
import org.compiere.process.ProcessCall;
import org.osgi.service.component.annotations.Component;

import com.metalsistem.nonbusinessday.process.GenerateNonBusinessDays;

@Component(immediate = true, service = IProcessFactory.class)
public class NonBusinessDayProcessFactory implements IProcessFactory {

	@Override
	public ProcessCall newProcessInstance(String className) {
		if (GenerateNonBusinessDays.class.getName().equals(className))
			return new GenerateNonBusinessDays();
		return null;
	}
}
