package com.idega.bpm.exe;

import java.security.AccessControlException;
import java.security.Permission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

import javax.annotation.Resource;

import org.jbpm.JbpmContext;
import org.jbpm.JbpmException;
import org.jbpm.context.exe.ContextInstance;
import org.jbpm.graph.exe.ProcessInstance;
import org.jbpm.graph.exe.Token;
import org.jbpm.taskmgmt.exe.TaskInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Required;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.idega.block.process.business.CaseManagersProvider;
import com.idega.block.process.business.CasesRetrievalManager;
import com.idega.block.process.business.ExternalEntityInterface;
import com.idega.bpm.BPMConstants;
import com.idega.core.business.DefaultSpringBean;
import com.idega.jbpm.BPMContext;
import com.idega.jbpm.JbpmCallback;
import com.idega.jbpm.data.dao.BPMDAO;
import com.idega.jbpm.exe.BPMDocument;
import com.idega.jbpm.exe.BPMEmailDocument;
import com.idega.jbpm.exe.BPMFactory;
import com.idega.jbpm.exe.ProcessDefinitionW;
import com.idega.jbpm.exe.ProcessInstanceW;
import com.idega.jbpm.exe.ProcessManager;
import com.idega.jbpm.exe.ProcessWatch;
import com.idega.jbpm.exe.TaskInstanceW;
import com.idega.jbpm.exe.TaskMgmtInstanceW;
import com.idega.jbpm.exe.impl.BPMDocumentImpl;
import com.idega.jbpm.exe.impl.BPMEmailDocumentImpl;
import com.idega.jbpm.identity.BPMAccessControlException;
import com.idega.jbpm.identity.BPMUser;
import com.idega.jbpm.identity.Role;
import com.idega.jbpm.identity.RolesManager;
import com.idega.jbpm.identity.permission.Access;
import com.idega.jbpm.identity.permission.BPMTypedPermission;
import com.idega.jbpm.identity.permission.PermissionsFactory;
import com.idega.jbpm.rights.Right;
import com.idega.jbpm.variables.BinaryVariable;
import com.idega.jbpm.variables.VariablesHandler;
import com.idega.jbpm.view.View;
import com.idega.jbpm.view.ViewSubmission;
import com.idega.user.business.UserBusiness;
import com.idega.user.data.User;
import com.idega.user.util.UserComparator;
import com.idega.util.CoreConstants;
import com.idega.util.ListUtil;
import com.idega.util.StringUtil;
import com.idega.util.datastructures.map.MapUtil;
import com.idega.util.expression.ELUtil;

/**
 * @author <a href="mailto:civilis@idega.com">Vytautas Čivilis</a>
 * @version $Revision: 1.37 $ Last modified: $Date: 2009/07/03 08:56:48 $ by $Author: valdas $
 */
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
@Service("defaultPIW")
public class DefaultBPMProcessInstanceW extends DefaultSpringBean implements ProcessInstanceW {

	private Long processInstanceId;
	private ProcessInstance processInstance;

	@Autowired
	private BPMContext bpmContext;
	private ProcessManager processManager;
	@Autowired
	private BPMFactory bpmFactory;

	@Autowired
	private PermissionsFactory permissionsFactory;

	@Autowired
	private BPMDAO bpmDAO;

	@Autowired
	private VariablesHandler variablesHandler;

	@Autowired
	@Qualifier("default")
	private TaskMgmtInstanceW taskMgmtInstance;

	public static final String email_fetch_process_name = "fetchEmails";

	public static final String add_attachement_process_name = "addAttachments";

	@Override
	@Transactional(readOnly = true)
	public List<TaskInstanceW> getAllTaskInstances() {
		// TODO: hide tasks of ended subprocesses
		return wrapTaskInstances(getUnfilteredProcessTaskInstances());
	}

	Collection<TaskInstance> getUnfilteredProcessTaskInstances() {
		return getProcessTaskInstances(null, null);
	}

	/**
	 * gets task instances
	 *
	 * @param excludedSubProcessesNames
	 *            - task instances of subprocesses listed here are excluded
	 * @param includedOnlySubProcessesNames
	 *            - only task instances of subprocesses listed here are included
	 * @return
	 */
	@Transactional(readOnly = true)
	List<TaskInstance> getProcessTaskInstances(final List<String> excludedSubProcessesNames, final List<String> includedOnlySubProcessesNames) {
		return getBpmContext().execute(new JbpmCallback() {

			@Override
			@SuppressWarnings("unchecked")
			public Object doInJbpm(JbpmContext context) throws JbpmException {
				ProcessInstance processInstance = getProcessInstance();

				final List<TaskInstance> taskInstances;
				if (includedOnlySubProcessesNames != null) {
					// only inserting task instances from subprocesses
					taskInstances = new ArrayList<TaskInstance>();
				} else {
					taskInstances = new ArrayList<TaskInstance>(processInstance.getTaskMgmtInstance().getTaskInstances());
				}

				taskInstances.addAll(getSubprocessesTaskInstances(processInstance, excludedSubProcessesNames, includedOnlySubProcessesNames));
				return taskInstances;
			}
		});
	}

	private boolean isFilterOutProcessInstance(String processName, final List<String> excludedSubProcessesNames, List<String> includedOnlySubProcessesNames) {
		return (includedOnlySubProcessesNames != null && !includedOnlySubProcessesNames.contains(processName)) ||
				(includedOnlySubProcessesNames == null && excludedSubProcessesNames != null && excludedSubProcessesNames.contains(processName));
	}

	@Override
	public List<Long> getIdsOfSubProcesses(final Long procInstId) {
		if (procInstId == null) {
			return null;
		}

		List<ProcessInstance> subProcesses = getAllSubprocesses(procInstId);
		if (ListUtil.isEmpty(subProcesses)) {
			return null;
		}

		List<Long> ids = new ArrayList<Long>();
		for (ProcessInstance subProcess: subProcesses) {
			ids.add(subProcess.getId());
		}
		return ids;
	}

	private List<ProcessInstance> getAllSubprocesses(ProcessInstance processInstance) {
		if (processInstance == null) {
			return null;
		}

		return getAllSubprocesses(processInstance.getId());
	}

	private List<ProcessInstance> getAllSubprocesses(Long procInstId) {
		List<ProcessInstance> subProcessInstances = getBpmDAO().getSubprocessInstancesOneLevel(procInstId);
		if(ListUtil.isEmpty(subProcessInstances)) {
			return Collections.emptyList();
		}
		List<ProcessInstance> childSubProcessInstances = new ArrayList<ProcessInstance>(subProcessInstances);
		for (ProcessInstance subProcess: subProcessInstances) {
			childSubProcessInstances.addAll(getAllSubprocesses(subProcess));
		}
		return childSubProcessInstances;
	}

	private Collection<TaskInstance> getSubprocessesTaskInstances(
			ProcessInstance processInstance,
			final List<String> excludedSubProcessesNames,
			final List<String> includedOnlySubProcessesNames
	) {
		List<ProcessInstance> subProcessInstances = getAllSubprocesses(processInstance);

		List<TaskInstance> taskInstances;

		if (!ListUtil.isEmpty(subProcessInstances)) {
			taskInstances = new ArrayList<TaskInstance>();
			for (ProcessInstance subProcessInstance : subProcessInstances) {
				if (!isFilterOutProcessInstance(subProcessInstance.getProcessDefinition().getName(),
				    excludedSubProcessesNames, includedOnlySubProcessesNames)) {

					@SuppressWarnings("unchecked")
					Collection<TaskInstance> subTaskInstances = subProcessInstance.getTaskMgmtInstance().getTaskInstances();

					if (subTaskInstances != null)
						taskInstances.addAll(subTaskInstances);
				}
			}
		} else
			taskInstances = Collections.emptyList();

		return taskInstances;
	}

	@Transactional(readOnly = true)
	public List<TaskInstanceW> getSubmittedTaskInstances(List<String> excludedSubProcessesNames) {
		return getSubmittedTaskInstances(excludedSubProcessesNames, DefaultBPMTaskInstanceW.PRIORITY_HIDDEN, DefaultBPMTaskInstanceW.PRIORITY_VALID_HIDDEN);
	}

	List<TaskInstanceW> getSubmittedTaskInstances(List<String> excludedSubProcessesNames, Integer... prioritiesToFilter) {
		Collection<TaskInstance> taskInstances = getProcessTaskInstances(excludedSubProcessesNames, null);
		if (ListUtil.isEmpty(taskInstances)) {
			return wrapTaskInstances(taskInstances);
		}

		List<Integer> prioritiesToFilterList = Arrays.asList(prioritiesToFilter);

		for (Iterator<TaskInstance> iterator = taskInstances.iterator(); iterator.hasNext();) {
			TaskInstance ti = iterator.next();

			try {
				if (ti == null) {
					getLogger().warning("Task instance is null in a collection of task instances: " + taskInstances);
					iterator.remove();
				} else if (!ti.hasEnded() || prioritiesToFilterList.contains(ti.getPriority()))
					// simply filtering out the not ended task instances
					iterator.remove();
			} catch (Exception e) {
				getLogger().log(Level.WARNING, "Error while getting submitted tasks for processes: " + excludedSubProcessesNames, e);
				iterator.remove();
			}
		}

		return wrapTaskInstances(taskInstances);
	}

	@Override
	@Transactional(readOnly = true)
	public List<TaskInstanceW> getSubmittedTaskInstances() {
		return getSubmittedTaskInstances(new ArrayList<String>(0));
	}

	@Override
	public List<TaskInstanceW> getSubmittedTaskInstances(String taskInstanceName) {
		if (StringUtil.isEmpty(taskInstanceName)) {
			return null;
		}

		List<TaskInstanceW> allSubmittedTaskInstances = getSubmittedTaskInstances();
		if (ListUtil.isEmpty(allSubmittedTaskInstances)) {
			return null;
		}

		Map<Date, TaskInstanceW> tasksByName = new HashMap<Date, TaskInstanceW>();
		for (TaskInstanceW tiW: allSubmittedTaskInstances) {
			TaskInstance ti = tiW.getTaskInstance();
			if (taskInstanceName.equals(ti.getName())) {
				tasksByName.put(ti.getEnd(), tiW);
			}
		}

		if (MapUtil.isEmpty(tasksByName)) {
			return null;
		}

		List<TaskInstanceW> tasks = new ArrayList<TaskInstanceW>();
		List<Date> dates = new ArrayList<Date>(tasksByName.keySet());
		Collections.sort(dates);
		for (Date date: dates) {
			tasks.add(tasksByName.get(date));
		}
		return tasks;
	}

	@Override
	public TaskInstanceW getLastSubmittedTaskInstance(String taskInstanceName) {
		List<TaskInstanceW> submittedTaskInstances = getSubmittedTaskInstances(taskInstanceName);
		if (ListUtil.isEmpty(submittedTaskInstances)) {
			return null;
		}

		return submittedTaskInstances.get(submittedTaskInstances.size() - 1);
	}

	@Override
	@Transactional(readOnly = true)
	public List<BPMDocument> getTaskDocumentsForUser(User user, Locale locale, boolean doShowExternalEntity) {
		List<TaskInstanceW> unfinishedTaskInstances = getAllUnfinishedTaskInstances();

		unfinishedTaskInstances = filterTasksByUserPermission(user, unfinishedTaskInstances);

		return getBPMDocuments(unfinishedTaskInstances, locale, doShowExternalEntity);
	}

	private List<TaskInstanceW> filterTasksByUserPermission(User user, List<TaskInstanceW> unfinishedTaskInstances) {
		PermissionsFactory permissionsFactory = getBpmFactory().getPermissionsFactory();
		RolesManager rolesManager = getBpmFactory().getRolesManager();

		for (Iterator<TaskInstanceW> iterator = unfinishedTaskInstances.iterator(); iterator.hasNext();) {
			TaskInstanceW tiw = iterator.next();
			TaskInstance ti = tiw.getTaskInstance();

			try {
				// check if task instance is eligible for viewing for user
				// provided

				// TODO: add user into permission
				Permission permission = permissionsFactory.getTaskInstanceSubmitPermission(false, ti);
				rolesManager.checkPermission(permission);
			} catch (BPMAccessControlException e) {
				iterator.remove();
			}
		}

		return unfinishedTaskInstances;
	}

	private List<TaskInstanceW> filterDocumentsByUserPermission(User user, List<TaskInstanceW> submittedTaskInstances) {
		PermissionsFactory permissionsFactory = getBpmFactory().getPermissionsFactory();
		RolesManager rolesManager = getBpmFactory().getRolesManager();

		for (Iterator<TaskInstanceW> iterator = submittedTaskInstances.iterator(); iterator.hasNext();) {
			TaskInstanceW tiw = iterator.next();
			TaskInstance ti = tiw.getTaskInstance();

			try {
				// check if task instance is eligible for viewing for user
				// provided

				// TODO: add user into permission
				Permission permission = permissionsFactory.getTaskInstanceViewPermission(true, ti);
				rolesManager.checkPermission(permission);
			} catch (BPMAccessControlException e) {
				iterator.remove();
			}
		}

		return submittedTaskInstances;
	}

	@Override
	@Transactional(readOnly = true)
	public List<BPMDocument> getSubmittedDocumentsForUser(User user, Locale locale, boolean doShowExternalEntity) {
		List<TaskInstanceW> submittedTaskInstances = getSubmittedTaskInstances(Arrays.asList(email_fetch_process_name));

		submittedTaskInstances = filterDocumentsByUserPermission(user, submittedTaskInstances);
		return getBPMDocuments(submittedTaskInstances, locale, doShowExternalEntity);
	}

	@Autowired(required=false)
	private ExternalEntityInterface externalEntityInterface;

	protected ExternalEntityInterface getExternalEntityInterface() {
		if (this.externalEntityInterface == null) {
			ELUtil.getInstance().autowire(this);
		}

		return this.externalEntityInterface;
	}

	private List<BPMDocument> getBPMDocuments(List<TaskInstanceW> tiws, Locale locale, boolean doShowExternalEntity) {
		List<BPMDocument> documents = new ArrayList<BPMDocument>(tiws.size());

		UserBusiness userBusiness = getServiceInstance(UserBusiness.class);
		for (TaskInstanceW tiw : tiws) {
			TaskInstance ti = tiw.getTaskInstance();

			// creating document representation
			BPMDocumentImpl bpmDoc = new BPMDocumentImpl();

			// get submitted by
			String actorId = ti.getActorId();
			String actorName = null;

			if (actorId != null) {
				try {
					User usr = userBusiness.getUser(Integer.parseInt(actorId));

					if (!doShowExternalEntity) {
						actorName = usr.getName();
					} else {
						ExternalEntityInterface eei = getExternalEntityInterface();
						if (eei != null) {
							actorName = eei.getName(usr);
						}

						if (StringUtil.isEmpty(actorName)) {
							actorName = usr.getName();
						} else {
							actorName = actorName + " (" + usr.getName() + ")";
						}
					}

				} catch (Exception e) {
					getLogger().log(Level.SEVERE, "Exception while resolving actor name for actorId: " + actorId, e);
					actorName = CoreConstants.EMPTY;
				}
			} else
				actorName = CoreConstants.EMPTY;

			String submittedBy;
			String assignedTo;

			if (ti.getEnd() == null) {
				// task
				submittedBy = CoreConstants.EMPTY;
				assignedTo = actorName;
			} else {
				// document
				submittedBy = actorName;
				assignedTo = CoreConstants.EMPTY;
			}

			bpmDoc.setTaskInstanceId(ti.getId());
			bpmDoc.setAssignedToName(assignedTo);
			bpmDoc.setSubmittedByName(submittedBy);
			bpmDoc.setDocumentName(tiw.getName(locale));
			bpmDoc.setCreateDate(ti.getCreate());
			bpmDoc.setEndDate(ti.getEnd());
			bpmDoc.setSignable(tiw.isSignable());
			bpmDoc.setOrder(tiw.getOrder());

			View view = tiw.getView();
			bpmDoc.setHasViewUI(view.hasViewForDisplay());

			documents.add(bpmDoc);
		}

		return documents;
	}

	@Override
	@Transactional(readOnly = true)
	public List<TaskInstanceW> getUnfinishedTaskInstances(Token rootToken) {
		ProcessInstance processInstance = rootToken.getProcessInstance();

		@SuppressWarnings("unchecked")
		Collection<TaskInstance> taskInstances = processInstance.getTaskMgmtInstance().getUnfinishedTasks(rootToken);

		return wrapTaskInstances(taskInstances);
	}

	@Override
	@Transactional(readOnly = true)
	public List<TaskInstanceW> getAllUnfinishedTaskInstances() {
		return getUnfinishedTaskInstancesForTask(null, DefaultBPMTaskInstanceW.PRIORITY_HIDDEN, DefaultBPMTaskInstanceW.PRIORITY_VALID_HIDDEN);
	}

	@Override
	@Transactional(readOnly = true)
	public List<TaskInstanceW> getUnfinishedTaskInstancesForTask(String taskName) {
		return getUnfinishedTaskInstancesForTask(taskName, DefaultBPMTaskInstanceW.PRIORITY_HIDDEN);
	}

	List<TaskInstanceW> getUnfinishedTaskInstancesForTask(String taskName, Integer... prioritiesToFilter) {
		Collection<TaskInstance> taskInstances = getUnfilteredProcessTaskInstances();
		if (ListUtil.isEmpty(taskInstances)) {
			return wrapTaskInstances(taskInstances);
		}

		List<Integer> prioritiesToFilterList = Arrays.asList(prioritiesToFilter);
		boolean filterByTaskName = !StringUtil.isEmpty(taskName);
		for (Iterator<TaskInstance> iterator = taskInstances.iterator(); iterator.hasNext();) {
			TaskInstance ti = iterator.next();

			// removing hidden, ended task instances, and task instances of ended
			// processes (i.e. subprocesses), also leaving on task for taskName, if taskName
			// provided
			Long id = null;
			try {
				if (ti == null) {
					getLogger().warning("Task instance is null in a collection of task instances: " + taskInstances);
					iterator.remove();
				} else {
					id = ti.getId();
					if (ti.hasEnded()
							|| prioritiesToFilterList.contains(ti.getPriority())
					        || ti.getProcessInstance().hasEnded()
					        || (filterByTaskName && !taskName.equals(ti.getTask().getName())))
						iterator.remove();
				}
			} catch (Exception e) {
				getLogger().log(Level.WARNING, "Error while getting unfinished tasks for the task (name=" + taskName + "). Unable to resolve if a task (id=" + id +
						") is unfinished - removing it!", e);
				iterator.remove();
			}
		}

		return wrapTaskInstances(taskInstances);
	}

	@Override
	public TaskInstanceW getSingleUnfinishedTaskInstanceForTask(String taskName) {
		List<TaskInstanceW> tiws = getUnfinishedTaskInstancesForTask(taskName);
		TaskInstanceW tiw;

		if (ListUtil.isEmpty(tiws))
			tiw = null;
		else {
			if (tiws.size() > 1)
				getLogger().warning("More than one unfinished task instance resolved for task=" + taskName + " in the process=" + getProcessInstanceId());

			tiw = tiws.iterator().next();
		}

		return tiw;
	}

	private List<TaskInstanceW> wrapTaskInstances(Collection<TaskInstance> taskInstances) {
		List<TaskInstanceW> instances = new ArrayList<TaskInstanceW>(taskInstances == null ? 0 : taskInstances.size());

		if (ListUtil.isEmpty(taskInstances)) {
			return instances;
		}

		for (TaskInstance instance : taskInstances) {
			TaskInstanceW tiw = getProcessManager().getTaskInstance(instance.getId());
			instances.add(tiw);
		}
		return instances;
	}

	@Override
	public Long getProcessInstanceId() {
		return processInstanceId;
	}

	@Override
	public void setProcessInstanceId(Long processInstanceId) {
		this.processInstanceId = processInstanceId;
	}

	public BPMContext getBpmContext() {
		return bpmContext;
	}

	public void setBpmContext(BPMContext bpmContext) {
		this.bpmContext = bpmContext;
	}

	public ProcessManager getProcessManager() {
		return processManager;
	}

	@Required
	@Resource(name = "defaultBpmProcessManager")
	public void setProcessManager(ProcessManager processManager) {
		this.processManager = processManager;
	}

	@Override
	@Transactional(readOnly = true)
	public ProcessInstance getProcessInstance() {
		processInstance = getBpmContext().execute(new JbpmCallback() {
			@Override
			public Object doInJbpm(JbpmContext context) throws JbpmException {
				return context.getProcessInstance(getProcessInstanceId());
			}
		});

		return processInstance;
	}

	@Override
	public void assignHandler(Integer handlerUserId) {
	}

	@Override
	public String getProcessDescription() {
		return null;
	}

	@Override
	public String getProcessIdentifier() {
		return null;
	}

	@Override
	@Transactional(readOnly = true)
	public ProcessDefinitionW getProcessDefinitionW() {
		Long pdId = getProcessInstance().getProcessDefinition().getId();
		return getProcessManager().getProcessDefinition(pdId);
	}

	@Override
	public Integer getHandlerId() {
		return null;
	}

	private List<User> usersConnectedToProcess = null;

	@Override
	@Transactional(readOnly = true)
	public List<User> getUsersConnectedToProcess() {
		if (usersConnectedToProcess != null) {
			return usersConnectedToProcess;
		}

		try {
			String procDefName = getProcessDefinitionW().getProcessDefinition().getName();
			@SuppressWarnings("unchecked")
			Map<String, Object> variables = getProcessInstance().getContextInstance().getVariables();
			usersConnectedToProcess = getBpmFactory().getBPMDAO().getUsersConnectedToProcess(getProcessInstanceId(), procDefName, variables);
		} catch (Exception e) {}
		if (usersConnectedToProcess != null) {
			return usersConnectedToProcess;
		}

		final Collection<User> users;
		try {
			Long processInstanceId = getProcessInstanceId();
			BPMTypedPermission perm = (BPMTypedPermission) getBpmFactory().getPermissionsFactory()
					.getRoleAccessPermission(processInstanceId, null, false);
			users = getBpmFactory().getRolesManager().getAllUsersForRoles(null, processInstanceId, perm);
		} catch (Exception e) {
			getLogger().log(Level.SEVERE, "Exception while resolving all process instance users", e);
			return null;
		}

		if (ListUtil.isEmpty(users)) {
			usersConnectedToProcess = new ArrayList<User>();
			return usersConnectedToProcess;
		}

		// using separate list, as the resolved one could be cashed (shared) and so
		usersConnectedToProcess = new ArrayList<User>(users);
		for (Iterator<User> iterator = usersConnectedToProcess.iterator(); iterator.hasNext();) {
			User user = iterator.next();
			String hideInContacts = user.getMetaData(BPMUser.HIDE_IN_CONTACTS);

			if (hideInContacts != null)
				// excluding ones, that should be hidden in contacts list
				iterator.remove();
		}

		try {
			Collections.sort(usersConnectedToProcess, new UserComparator(getCurrentLocale()));
		} catch (Exception e) {
			getLogger().log(Level.SEVERE, "Exception while sorting contacts list (" + usersConnectedToProcess + ")", e);
		}

		return usersConnectedToProcess;
	}

	@Override
	public boolean hasHandlerAssignmentSupport() {
		return false;
	}

	@Override
	public void setContactsPermission(Role role, Integer userId) {
		Long processInstanceId = getProcessInstanceId();
		getBpmFactory().getRolesManager().setContactsPermission(role, processInstanceId, userId);
	}

	public BPMFactory getBpmFactory() {
		return bpmFactory;
	}

	public void setBpmFactory(BPMFactory bpmFactory) {
		this.bpmFactory = bpmFactory;
	}

	@Override
	public ProcessWatch getProcessWatcher() {
		return null;
	}

	/**
	 * checks right for process instance and current logged in user
	 *
	 * @param right
	 * @return
	 */
	@Override
	public boolean hasRight(Right right) {
		return hasRight(right, null);
	}

	/**
	 * checks right for process instance and user provided
	 *
	 * @param right
	 * @param user
	 *            to check right against
	 * @return
	 */
	@Override
	@Transactional(readOnly = true)
	public boolean hasRight(Right right, User user) {
		switch (right) {
			case processHandler:
				return hasPermission(Access.caseHandler, user);

			case commentsViewer:
				return hasPermission(Access.seeComments, user) || hasPermission(Access.writeComments, user);

			default:
				throw new IllegalArgumentException("Right type " + right + " not supported for cases process instance");
		}
	}

	private boolean hasPermission(Access access, User user) {
		try {
			Permission perm = getBpmFactory().getPermissionsFactory().getAccessPermission(getProcessInstanceId(), access, user);
			getBpmFactory().getRolesManager().checkPermission(perm);
			return true;
		} catch (AccessControlException e) {
			return false;
		}
	}

	protected BPMDAO getBpmDAO() {
		return bpmDAO;
	}

	@Override
	public List<BPMEmailDocument> getAttachedEmails(User user) {
		return getAttachedEmails(user, false);
	}

	@Override
	@Transactional(readOnly = true)
	public List<BPMEmailDocument> getAttachedEmails(User user, boolean fetchMessage) {
		List<String> included = new ArrayList<String>(1);
		included.add(email_fetch_process_name);
		List<TaskInstance> emailsTaskInstances = getProcessTaskInstances(null, included);

		emailsTaskInstances = filterEmailsTaskInstances(emailsTaskInstances);

		List<BPMEmailDocument> bpmEmailDocs = new ArrayList<BPMEmailDocument>(emailsTaskInstances.size());

		for (TaskInstance emailTaskInstance : emailsTaskInstances) {
			Map<String, Object> vars = getVariablesHandler().populateVariables(emailTaskInstance.getId());

			String subject = (String) vars.get(BPMConstants.VAR_SUBJECT);
			String text = null;
			if (fetchMessage)
				text = (String) vars.get(BPMConstants.VAR_TEXT);
			String fromPersonal = (String) vars.get(BPMConstants.VAR_FROM);
			String fromAddress = (String) vars.get(BPMConstants.VAR_FROM_ADDRESS);

			BPMEmailDocument bpmEmailDocument = new BPMEmailDocumentImpl();
			bpmEmailDocument.setTaskInstanceId(emailTaskInstance.getId());
			bpmEmailDocument.setSubject(subject);
			bpmEmailDocument.setMessage(text);
			bpmEmailDocument.setFromAddress(fromAddress);
			bpmEmailDocument.setFromPersonal(fromPersonal);
			bpmEmailDocument.setEndDate(emailTaskInstance.getEnd());
			bpmEmailDocument.setDocumentName(emailTaskInstance.getName());
			bpmEmailDocument.setCreateDate(emailTaskInstance.getCreate());
			bpmEmailDocs.add(bpmEmailDocument);
		}

		return bpmEmailDocs;
	}

	List<TaskInstance> filterEmailsTaskInstances(List<TaskInstance> emailsTaskInstances) {
		for (Iterator<TaskInstance> iterator = emailsTaskInstances.iterator(); iterator.hasNext();) {
			TaskInstance taskInstance = iterator.next();

			try {
				if (taskInstance == null) {
					iterator.remove();
				} else if (!taskInstance.hasEnded()) {
					iterator.remove();
				} else {
					try {
						Permission permission = getPermissionsFactory().getTaskInstanceViewPermission(true, taskInstance);
						getBpmFactory().getRolesManager().checkPermission(permission);
					} catch (BPMAccessControlException e) {
						iterator.remove();
					}
				}
			} catch (Exception e) {
				getLogger().log(Level.WARNING, "Error getting emails from the list of task instances: (" +
						(emailsTaskInstances.size() > 10 ? emailsTaskInstances.subList(0, 9) : emailsTaskInstances) + "). Total number of tasks: " + emailsTaskInstances.size(), e);
				iterator.remove();
			}
		}

		return emailsTaskInstances;
	}

	/**
	 * @return all attachments that where attached to process(including subprocesses), and that are
	 *         not hidden (binVar.getHidden() == false)
	 */
	@Override
	@Transactional(readOnly = true)
	public List<BinaryVariable> getAttachments() {
		List<TaskInstanceW> taskInstances = getAllTaskInstances();
		List<BinaryVariable> attachments = new ArrayList<BinaryVariable>();

		for (Iterator<TaskInstanceW> iterator = taskInstances.iterator(); iterator.hasNext();) {
			attachments.addAll(iterator.next().getAttachments());
		}

		return attachments;
	}

	@Override
	@Transactional(readOnly = true)
	public TaskInstanceW getStartTaskInstance() {
		long id = getProcessDefinitionW().getProcessDefinition().getTaskMgmtDefinition().getStartTask().getId();
		for (TaskInstanceW taskInstanceW : getAllTaskInstances()) {
			if (taskInstanceW.getTaskInstance().getTask().getId() == id) {
				return taskInstanceW;
			}
		}
		return null;
	}

	@Override
	@Transactional(readOnly = true)
	public boolean hasEnded() {
		return getProcessInstance().hasEnded();
	}

	public VariablesHandler getVariablesHandler() {
		return variablesHandler;
	}

	public PermissionsFactory getPermissionsFactory() {
		return permissionsFactory;
	}

	@Override
	public Collection<Role> getRolesContactsPermissions(Integer userId) {
		Collection<Role> roles = getBpmFactory().getRolesManager().getUserPermissionsForRolesContacts(getProcessInstanceId(), userId);
		return roles;
	}

	@Override
	public Object getVariableLocally(final String variableName, Token token) {
		if (token == null)
			token = getProcessInstance().getRootToken();

		ContextInstance contextInstnace = token.getProcessInstance().getContextInstance();

		Object val = contextInstnace.getVariableLocally(variableName, token);
		return val;
	}

	@Override
	public TaskMgmtInstanceW getTaskMgmtInstance() {
		return taskMgmtInstance.init(this);
	}

	@Override
	public User getOwner() {
		//	TODO: add implementation for none case based processes
		CaseManagersProvider managersProvider = ELUtil.getInstance().getBean(CaseManagersProvider.beanIdentifier);
		if (managersProvider == null) {
			return null;
		}

		List<CasesRetrievalManager> retrievalManagers = managersProvider.getCaseManagers();
		if (ListUtil.isEmpty(retrievalManagers)) {
			return null;
		}

		Long id = getProcessInstanceId();

		User owner = null;
		for (Iterator<CasesRetrievalManager> retrievalManagersIter = retrievalManagers.iterator(); (retrievalManagersIter.hasNext() && owner == null);) {
			owner = retrievalManagersIter.next().getCaseOwner(id);
		}

		return owner;
	}

	@Override
	@Transactional(readOnly = false)
	public boolean doSubmitSharedTask(String taskName, Map<String, Object> variables) {
		if (StringUtil.isEmpty(taskName))
			return Boolean.FALSE;

		try {
			List<TaskInstanceW> unfinishedTasks = getUnfinishedTaskInstancesForTask(taskName);
			if (ListUtil.isEmpty(unfinishedTasks)) {
				getLogger().warning("Unable to find shared task '" + taskName + "' for process instance with ID: " + getProcessInstanceId());
				return false;
			}

			TaskInstanceW unfinishedTask = unfinishedTasks.iterator().next();
			View view = unfinishedTask.loadView();
			ViewSubmission viewSubmission = getBpmFactory().getViewSubmission();

			Map<String, Object> currentVariables = view.resolveVariables();
			if (currentVariables == null)
				currentVariables = variables;
			else if (variables != null) {
				for (Map.Entry<String, Object> entry: variables.entrySet()) {
					currentVariables.put(entry.getKey(), entry.getValue());
				}
			}

			viewSubmission.populateParameters(view.resolveParameters());
			viewSubmission.populateVariables(currentVariables);

			Long viewTaskInstanceId = view.getTaskInstanceId();
			TaskInstanceW viewTIW = getBpmFactory().getProcessManagerByTaskInstanceId(viewTaskInstanceId).getTaskInstance(viewTaskInstanceId);
			viewTIW.submit(viewSubmission);

			return Boolean.TRUE;
		} catch (Exception e) {
			getLogger().log(Level.WARNING, "Error submiting task '" + taskName + "' for process instance: " + getProcessInstanceId(), e);
		}

		return Boolean.FALSE;
	}

	@Override
	public List<BPMDocument> getSubmittedDocumentsForUser(User user,
			Locale locale) {
		return getSubmittedDocumentsForUser(user, locale, false);
	}

	@Override
	public List<BPMDocument> getTaskDocumentsForUser(User user, Locale locale) {
		return getTaskDocumentsForUser(user, locale, false);
	}

	@Override
	public Object getValueForTaskInstance(String taskInstanceName, String variable) {
		List<TaskInstanceW> submittedTiWs = getSubmittedTaskInstances(taskInstanceName);
		return getLatestValue(submittedTiWs, variable, submittedTiWs.size() - 1);
	}

	@Override
	public Object getValueForTaskInstance(List<TaskInstanceW> submittedTiWs, String variable) {
		if (ListUtil.isEmpty(submittedTiWs)) {
			return null;
		}
		return getLatestValue(submittedTiWs, variable, submittedTiWs.size() - 1);
	}

	private Object getLatestValue(List<TaskInstanceW> submittedTiWs, String variable, int index) {
		if (ListUtil.isEmpty(submittedTiWs) || index < 0 || index >= submittedTiWs.size()) {
			return null;
		}

		Object value = submittedTiWs.get(index).getVariable(variable);
		if (value != null) {
			return value;
		}

		index--;
		return getLatestValue(submittedTiWs, variable, index);
	}

	@Override
	public String getProcessOwner() {
		return null;
	}

}