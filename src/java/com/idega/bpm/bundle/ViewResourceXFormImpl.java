package com.idega.bpm.bundle;

import java.io.IOException;
import java.io.InputStream;

import javax.xml.parsers.DocumentBuilder;

import org.w3c.dom.Document;

import com.idega.bpm.xformsview.XFormsView;
import com.idega.idegaweb.IWMainApplication;
import com.idega.jbpm.bundle.ProcessBundleResources;
import com.idega.jbpm.view.ViewResource;
import com.idega.util.xml.XmlUtil;
import com.idega.xformsmanager.business.DocumentManager;
import com.idega.xformsmanager.business.DocumentManagerFactory;

/**
 * @author <a href="civilis@idega.com">Vytautas Čivilis</a>
 * @version $Revision: 1.1 $ Last modified: $Date: 2009/01/25 15:44:13 $ by
 *          $Author: civilis $
 */
public class ViewResourceXFormImpl implements ViewResource {

	private String viewId;
	private String viewType;
	private String processName;
	private String taskName;
	private String pathWithinBundle;
	private DocumentManagerFactory documentManagerFactory;
	private ProcessBundleResources bundleResources;

	public void store(IWMainApplication iwma) throws IOException {

		InputStream is = null;

		try {
			is = getBundleResources().getResourceIS(getPathWithinBundle());
			DocumentManager documentManager = getDocumentManagerFactory()
					.newDocumentManager(iwma);
			DocumentBuilder builder = XmlUtil.getDocumentBuilder();

			Document xformXml = builder.parse(is);

			// TODO: open form lazy here
			com.idega.xformsmanager.business.Document form = documentManager
					.openForm(xformXml);

			form.setFormType(XFormsView.FORM_TYPE);

			String basePath = "/bpm/" + getProcessName() + "/forms/";

			form.save(basePath);

			viewId = form.getFormId().toString();

		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {

			if (is != null)
				is.close();
		}
	}

	public String getTaskName() {
		return taskName;
	}

	public void setTaskName(String taskName) {
		this.taskName = taskName;
	}

	public void setResourceLocation(ProcessBundleResources bundleResources,
			String pathWithinBundle) {

		this.bundleResources = bundleResources;
		this.pathWithinBundle = pathWithinBundle;
	}

	public DocumentManagerFactory getDocumentManagerFactory() {
		return documentManagerFactory;
	}

	public void setDocumentManagerFactory(
			DocumentManagerFactory documentManagerFactory) {
		this.documentManagerFactory = documentManagerFactory;
	}

	public String getPathWithinBundle() {
		return pathWithinBundle;
	}

	public ProcessBundleResources getBundleResources() {
		return bundleResources;
	}

	public String getProcessName() {
		return processName;
	}

	public void setProcessName(String processName) {
		this.processName = processName;
	}

	public void setViewResourceIdentifier(String viewResourceIdentifier) {
	}

	public String getViewType() {
		return viewType;
	}

	public void setViewType(String viewType) {
		this.viewType = viewType;
	}

	public String getViewId() {
		return viewId;
	}
}