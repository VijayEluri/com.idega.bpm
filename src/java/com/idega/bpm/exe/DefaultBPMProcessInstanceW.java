package com.idega.bpm.exe;

import java.security.AccessControlException;
import java.security.Permission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Resource;

import org.jbpm.JbpmContext;
import org.jbpm.JbpmException;
import org.jbpm.context.exe.ContextInstance;
import org.jbpm.graph.exe.ProcessInstance;
import org.jbpm.graph.exe.Token;
import org.jbpm.graph.node.TaskNode;
import org.jbpm.taskmgmt.exe.TaskInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Required;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.idega.business.IBOLookup;
import com.idega.business.IBOLookupException;
import com.idega.business.IBORuntimeException;
import com.idega.idegaweb.IWApplicationContext;
import com.idega.idegaweb.IWMainApplication;
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
import com.idega.user.business.UserBusiness;
import com.idega.user.data.User;
import com.idega.user.util.UserComparator;
import com.idega.util.CoreConstants;
import com.idega.util.CoreUtil;
import com.idega.util.ListUtil;
import com.idega.util.StringUtil;

/**
 * @author <a href="mailto:civilis@idega.com">Vytautas Čivilis</a>
 * @version $Revision: 1.29 $ Last modified: $Date: 2009/03/19 17:43:15 $ by $Author: juozas $
 */
@Scope("prototype")
@Service("defaultPIW")
public class DefaultBPMProcessInstanceW implements ProcessInstanceW {
	
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
	
	public static final String email_fetch_process_name = "fetchEmails";
	
	public static final String add_attachement_process_name = "addAttachments";
	
	// private static final String CASHED_TASK_NAMES =
	// "defaultBPM_taskinstance_names";
	
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
	Collection<TaskInstance> getProcessTaskInstances(
	        final List<String> excludedSubProcessesNames,
	        final List<String> includedOnlySubProcessesNames) {
		
		return getBpmContext().execute(new JbpmCallback() {
			
			@SuppressWarnings("unchecked")
			public Object doInJbpm(JbpmContext context) throws JbpmException {
				
				ProcessInstance processInstance = getProcessInstance();
				
				final Collection<TaskInstance> taskInstances;
				
				if (includedOnlySubProcessesNames != null) {
					
					// only inserting task instances from subprocesses
					taskInstances = new ArrayList<TaskInstance>();
				} else {
					
					taskInstances = new ArrayList<TaskInstance>(processInstance
					        .getTaskMgmtInstance().getTaskInstances());
					
				}
				
				taskInstances.addAll(getSubprocessesTaskInstances(
				    processInstance, excludedSubProcessesNames,
				    includedOnlySubProcessesNames));
				
				return taskInstances;
			}
		});
	}
	
	private boolean isFilterOutProcessInstance(String processName,
	        final List<String> excludedSubProcessesNames,
	        final List<String> includedOnlySubProcessesNames) {
		
		return (includedOnlySubProcessesNames != null && !includedOnlySubProcessesNames
		        .contains(processName))
		        || (includedOnlySubProcessesNames == null
		                && excludedSubProcessesNames != null && excludedSubProcessesNames
		                .contains(processName));
	}
	
	private Collection<TaskInstance> getSubprocessesTaskInstances(
	        ProcessInstance processInstance,
	        final List<String> excludedSubProcessesNames,
	        final List<String> includedOnlySubProcessesNames) {
		
		List<ProcessInstance> subProcessInstances = getBpmDAO()
		        .getSubprocessInstancesOneLevel(processInstance.getId());
		
		List<TaskInstance> taskInstances;
		
		if (!subProcessInstances.isEmpty()) {
			
			taskInstances = new ArrayList<TaskInstance>();
			
			for (ProcessInstance subProcessInstance : subProcessInstances) {
				
				if (!isFilterOutProcessInstance(subProcessInstance
				        .getProcessDefinition().getName(),
				    excludedSubProcessesNames, includedOnlySubProcessesNames)) {
					
					@SuppressWarnings("unchecked")
					Collection<TaskInstance> subTaskInstances = subProcessInstance
					        .getTaskMgmtInstance().getTaskInstances();
					
					if (subTaskInstances != null)
						taskInstances.addAll(subTaskInstances);
				}
			}
		} else
			taskInstances = Collections.emptyList();
		
		return taskInstances;
	}
	
	@Transactional(readOnly = true)
	public List<TaskInstanceW> getSubmittedTaskInstances(
	        List<String> excludedSubProcessesNames) {
		
		return getSubmittedTaskInstances(excludedSubProcessesNames,
		    DefaultBPMTaskInstanceW.PRIORITY_HIDDEN,
		    DefaultBPMTaskInstanceW.PRIORITY_VALID_HIDDEN);
	}
	
	List<TaskInstanceW> getSubmittedTaskInstances(
	        List<String> excludedSubProcessesNames,
	        Integer... prioritiesToFilter) {
		
		Collection<TaskInstance> taskInstances = getProcessTaskInstances(
		    excludedSubProcessesNames, null);
		
		List<Integer> prioritiesToFilterList = Arrays
		        .asList(prioritiesToFilter);
		
		for (Iterator<TaskInstance> iterator = taskInstances.iterator(); iterator
		        .hasNext();) {
			TaskInstance ti = iterator.next();
			
			if (!ti.hasEnded()
			        || prioritiesToFilterList.contains(ti.getPriority()))
				// simply filtering out the not ended task instances
				iterator.remove();
		}
		
		return wrapTaskInstances(taskInstances);
	}
	
	@Transactional(readOnly = true)
	public List<TaskInstanceW> getSubmittedTaskInstances() {
		return getSubmittedTaskInstances(null);
	}
	
	@Transactional(readOnly = true)
	public List<BPMDocument> getTaskDocumentsForUser(User user, Locale locale) {
		
		List<TaskInstanceW> unfinishedTaskInstances = getAllUnfinishedTaskInstances();
		
		unfinishedTaskInstances = filterTasksByUserPermission(user,
		    unfinishedTaskInstances);
		
		return getBPMDocuments(unfinishedTaskInstances, locale);
	}
	
	private List<TaskInstanceW> filterTasksByUserPermission(User user,
	        List<TaskInstanceW> unfinishedTaskInstances) {
		
		PermissionsFactory permissionsFactory = getBpmFactory()
		        .getPermissionsFactory();
		RolesManager rolesManager = getBpmFactory().getRolesManager();
		
		for (Iterator<TaskInstanceW> iterator = unfinishedTaskInstances
		        .iterator(); iterator.hasNext();) {
			
			TaskInstanceW tiw = iterator.next();
			TaskInstance ti = tiw.getTaskInstance();
			
			try {
				// check if task instance is eligible for viewing for user
				// provided
				
				// TODO: add user into permission
				Permission permission = permissionsFactory
				        .getTaskInstanceSubmitPermission(false, ti);
				rolesManager.checkPermission(permission);
				
			} catch (BPMAccessControlException e) {
				iterator.remove();
			}
		}
		
		return unfinishedTaskInstances;
	}
	
	private List<TaskInstanceW> filterDocumentsByUserPermission(User user,
	        List<TaskInstanceW> submittedTaskInstances) {
		
		PermissionsFactory permissionsFactory = getBpmFactory()
		        .getPermissionsFactory();
		RolesManager rolesManager = getBpmFactory().getRolesManager();
		
		for (Iterator<TaskInstanceW> iterator = submittedTaskInstances
		        .iterator(); iterator.hasNext();) {
			TaskInstanceW tiw = iterator.next();
			TaskInstance ti = tiw.getTaskInstance();
			
			try {
				// check if task instance is eligible for viewing for user
				// provided
				
				// TODO: add user into permission
				Permission permission = permissionsFactory
				        .getTaskInstanceViewPermission(true, ti);
				rolesManager.checkPermission(permission);
				
			} catch (BPMAccessControlException e) {
				iterator.remove();
			}
		}
		
		return submittedTaskInstances;
	}
	
	@Transactional(readOnly = true)
	public List<BPMDocument> getSubmittedDocumentsForUser(User user,
	        Locale locale) {
		
		List<TaskInstanceW> submittedTaskInstances = getSubmittedTaskInstances(Arrays
		        .asList(email_fetch_process_name));
		
		submittedTaskInstances = filterDocumentsByUserPermission(user,
		    submittedTaskInstances);
		
		return getBPMDocuments(submittedTaskInstances, locale);
	}
	
	private List<BPMDocument> getBPMDocuments(List<TaskInstanceW> tiws,
	        Locale locale) {
		
		ArrayList<BPMDocument> documents = new ArrayList<BPMDocument>(tiws
		        .size());
		
		UserBusiness userBusiness = getUserBusiness();
		
		for (TaskInstanceW tiw : tiws) {
			TaskInstance ti = tiw.getTaskInstance();
			
			// creating document representation
			BPMDocumentImpl bpmDoc = new BPMDocumentImpl();
			
			// get submitted by
			String actorId = ti.getActorId();
			String actorName;
			
			if (actorId != null) {
				
				try {
					User usr = userBusiness.getUser(Integer.parseInt(actorId));
					actorName = usr.getName();
					
				} catch (Exception e) {
					Logger.getLogger(getClass().getName()).log(
					    Level.SEVERE,
					    "Exception while resolving actor name for actorId: "
					            + actorId, e);
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
			
			// string representation of end date, if any
			
			bpmDoc.setTaskInstanceId(ti.getId());
			bpmDoc.setAssignedToName(assignedTo);
			bpmDoc.setSubmittedByName(submittedBy);
			bpmDoc.setDocumentName(tiw.getName(locale));
			bpmDoc.setCreateDate(ti.getCreate());
			bpmDoc.setEndDate(ti.getEnd());
			bpmDoc.setSignable(tiw.isSignable());
			
			documents.add(bpmDoc);
		}
		
		return documents;
	}
	
	@Transactional(readOnly = true)
	public List<TaskInstanceW> getUnfinishedTaskInstances(Token rootToken) {
		
		ProcessInstance processInstance = rootToken.getProcessInstance();
		
		@SuppressWarnings("unchecked")
		Collection<TaskInstance> taskInstances = processInstance
		        .getTaskMgmtInstance().getUnfinishedTasks(rootToken);
		
		return wrapTaskInstances(taskInstances);
	}
	
	@Transactional(readOnly = true)
	public List<TaskInstanceW> getAllUnfinishedTaskInstances() {
		
		return getUnfinishedTaskInstancesForTask(null,
		    DefaultBPMTaskInstanceW.PRIORITY_HIDDEN,
		    DefaultBPMTaskInstanceW.PRIORITY_VALID_HIDDEN);
	}
	
	@Transactional(readOnly = true)
	public List<TaskInstanceW> getUnfinishedTaskInstancesForTask(String taskName) {
		
		return getUnfinishedTaskInstancesForTask(taskName,
		    DefaultBPMTaskInstanceW.PRIORITY_HIDDEN);
	}
	
	List<TaskInstanceW> getUnfinishedTaskInstancesForTask(String taskName,
	        Integer... prioritiesToFilter) {
		
		Collection<TaskInstance> taskInstances = getUnfilteredProcessTaskInstances();
		
		List<Integer> prioritiesToFilterList = Arrays
		        .asList(prioritiesToFilter);
		
		boolean filterByTaskName = !StringUtil.isEmpty(taskName);
		
		for (Iterator<TaskInstance> iterator = taskInstances.iterator(); iterator
		        .hasNext();) {
			TaskInstance ti = iterator.next();
			
			// removing hidden, ended task instances, and task insances of ended
			// processes (i.e. subprocesses), also leaving on task for taskName, if taskName
			// provided
			if (ti.hasEnded()
			        || prioritiesToFilterList.contains(ti.getPriority())
			        || ti.getProcessInstance().hasEnded()
			        || (filterByTaskName && !taskName.equals(ti.getTask()
			                .getName())))
				iterator.remove();
		}
		
		return wrapTaskInstances(taskInstances);
	}
	
	public TaskInstanceW getSingleUnfinishedTaskInstanceForTask(String taskName) {
		
		List<TaskInstanceW> tiws = getUnfinishedTaskInstancesForTask(taskName);
		TaskInstanceW tiw;
		
		if (ListUtil.isEmpty(tiws))
			tiw = null;
		else {
			
			if (tiws.size() > 1)
				Logger.getLogger(getClass().getName()).log(
				    Level.WARNING,
				    "More than one unfinished task instance resolved for task="
				            + taskName + " in the process="
				            + getProcessInstanceId());
			
			tiw = tiws.iterator().next();
		}
		
		return tiw;
	}
	
	private List<TaskInstanceW> wrapTaskInstances(
	        Collection<TaskInstance> taskInstances) {
		ArrayList<TaskInstanceW> instances = new ArrayList<TaskInstanceW>(
		        taskInstances.size());
		
		for (TaskInstance instance : taskInstances) {
			TaskInstanceW tiw = getProcessManager().getTaskInstance(
			    instance.getId());
			instances.add(tiw);
		}
		
		return instances;
	}
	
	public Long getProcessInstanceId() {
		return processInstanceId;
	}
	
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
	
	@Transactional(readOnly = true)
	public ProcessInstance getProcessInstance() {
		
		if (true || (processInstance == null && getProcessInstanceId() != null)) {
			
			processInstance = getBpmContext().execute(new JbpmCallback() {
				
				public Object doInJbpm(JbpmContext context)
				        throws JbpmException {
					return context.getProcessInstance(getProcessInstanceId());
				}
			});
			
		} else if (processInstance != null) {
			processInstance = getBpmContext().execute(new JbpmCallback() {
				
				public Object doInJbpm(JbpmContext context)
				        throws JbpmException {
					
					return getBpmContext().mergeProcessEntity(processInstance);
				}
			});
		}
		return processInstance;
	}
	
	public void assignHandler(Integer handlerUserId) {
	}
	
	public String getProcessDescription() {
		
		return null;
	}
	
	public String getProcessIdentifier() {
		
		return null;
	}
	
	@Transactional(readOnly = true)
	public ProcessDefinitionW getProcessDefinitionW() {
		
		Long pdId = getProcessInstance().getProcessDefinition().getId();
		return getProcessManager().getProcessDefinition(pdId);
	}
	
	public Integer getHandlerId() {
		
		return null;
	}
	
	@Transactional(readOnly = true)
	public List<User> getUsersConnectedToProcess() {
		
		final Collection<User> users;
		
		try {
			Long processInstanceId = getProcessInstanceId();
			BPMTypedPermission perm = (BPMTypedPermission) getBpmFactory()
			        .getPermissionsFactory().getRoleAccessPermission(
			            processInstanceId, null, false);
			users = getBpmFactory().getRolesManager().getAllUsersForRoles(null,
			    processInstanceId, perm);
			
		} catch (Exception e) {
			Logger.getLogger(getClass().getName()).log(Level.SEVERE,
			    "Exception while resolving all process instance users", e);
			return null;
		}
		
		if (!ListUtil.isEmpty(users)) {
			
			// using separate list, as the resolved one could be cashed (shared)
			// and so
			ArrayList<User> connectedPeople = new ArrayList<User>(users);
			
			for (Iterator<User> iterator = connectedPeople.iterator(); iterator
			        .hasNext();) {
				
				User user = iterator.next();
				String hideInContacts = user
				        .getMetaData(BPMUser.HIDE_IN_CONTACTS);
				
				if (hideInContacts != null)
					// excluding ones, that should be hidden in contacts list
					iterator.remove();
			}
			
			try {
				Collections.sort(connectedPeople, new UserComparator(CoreUtil
				        .getIWContext().getCurrentLocale()));
			} catch (Exception e) {
				Logger.getLogger(getClass().getName()).log(
				    Level.SEVERE,
				    "Exception while sorting contacts list (" + connectedPeople
				            + ")", e);
			}
			
			return connectedPeople;
		}
		
		return null;
	}
	
	public boolean hasHandlerAssignmentSupport() {
		
		return false;
	}
	
	public void setContactsPermission(Role role, Integer userId) {
		
		Long processInstanceId = getProcessInstanceId();
		
		getBpmFactory().getRolesManager().setContactsPermission(role,
		    processInstanceId, userId);
	}
	
	public BPMFactory getBpmFactory() {
		return bpmFactory;
	}
	
	public void setBpmFactory(BPMFactory bpmFactory) {
		this.bpmFactory = bpmFactory;
	}
	
	public ProcessWatch getProcessWatcher() {
		return null;
	}
	
	/*
	 * public String getName(Locale locale) { final IWMainApplication iwma =
	 * getIWma();
	 * 
	 * @SuppressWarnings("unchecked") Map<Long, Map<Locale, String>>
	 * cashTaskNames = IWCacheManager2
	 * .getInstance(iwma).getCache(CASHED_TASK_NAMES); final Map<Locale, String>
	 * names; final Long taskInstanceId = getProcessDefinitionW()
	 * .getProcessDefinition().getTaskMgmtDefinition().getStartTask() .getId();
	 * 
	 * if (cashTaskNames.containsKey(taskInstanceId)) { names =
	 * cashTaskNames.get(taskInstanceId); } else { names = new HashMap<Locale,
	 * String>(5); cashTaskNames.put(taskInstanceId, names); } final String
	 * name;
	 * 
	 * if (names.containsKey(locale)) name = names.get(locale); else { View
	 * taskInstanceView = loadView(); name =
	 * taskInstanceView.getDisplayName(locale); names.put(locale, name); }
	 * 
	 * return name; }
	 */

	/*
	 * private IWMainApplication getIWma() { final IWContext iwc =
	 * CoreUtil.getIWContext(); final IWMainApplication iwma;
	 * 
	 * if (iwc != null) { iwma = iwc.getIWMainApplication(); } else { iwma =
	 * IWMainApplication.getDefaultIWMainApplication(); }
	 * 
	 * return iwma; }
	 */

	/*
	 * public View loadView() { Long taskId =
	 * getProcessDefinitionW().getProcessDefinition()
	 * .getTaskMgmtDefinition().getStartTask().getId(); JbpmContext ctx =
	 * getIdegaJbpmContext().createJbpmContext();
	 * 
	 * try { List<String> preferred = new ArrayList<String>(1);
	 * preferred.add(XFormsView.VIEW_TYPE);
	 * 
	 * View view = getBpmFactory().getViewByTask(taskId, false, preferred);
	 * 
	 * return view;
	 * 
	 * } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new
	 * RuntimeException(e); } finally {
	 * getIdegaJbpmContext().closeAndCommit(ctx); } }
	 */

	/**
	 * checks right for process instance and current logged in user
	 * 
	 * @param right
	 * @return
	 */
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
	@Transactional(readOnly = true)
	public boolean hasRight(Right right, User user) {
		
		switch (right) {
			case processHandler:

				try {
					Permission perm = getBpmFactory().getPermissionsFactory()
					        .getAccessPermission(getProcessInstanceId(),
					            Access.caseHandler, user);
					getBpmFactory().getRolesManager().checkPermission(perm);
					
					return true;
					
				} catch (AccessControlException e) {
					return false;
				}
				
			default:
				throw new IllegalArgumentException("Right type " + right
				        + " not supported for cases process instance");
		}
	}
	
	BPMDAO getBpmDAO() {
		return bpmDAO;
	}
	
	@Transactional(readOnly = true)
	public List<BPMEmailDocument> getAttachedEmails(User user) {
		
		ArrayList<String> included = new ArrayList<String>(1);
		included.add(email_fetch_process_name);
		Collection<TaskInstance> emailsTaskInstances = getProcessTaskInstances(
		    null, included);
		
		emailsTaskInstances = filterEmailsTaskInstances(emailsTaskInstances);
		
		List<BPMEmailDocument> bpmEmailDocs = new ArrayList<BPMEmailDocument>(
		        emailsTaskInstances.size());
		
		for (TaskInstance emailTaskInstance : emailsTaskInstances) {
			
			Map<String, Object> vars = getVariablesHandler().populateVariables(
			    emailTaskInstance.getId());
			
			String subject = (String) vars.get("string_subject");
			String fromPersonal = (String) vars.get("string_fromPersonal");
			String fromAddress = (String) vars.get("string_fromAddress");
			
			BPMEmailDocument bpmEmailDocument = new BPMEmailDocumentImpl();
			bpmEmailDocument.setTaskInstanceId(emailTaskInstance.getId());
			bpmEmailDocument.setSubject(subject);
			bpmEmailDocument.setFromAddress(fromAddress);
			bpmEmailDocument.setFromPersonal(fromPersonal);
			bpmEmailDocument.setEndDate(emailTaskInstance.getEnd());
			bpmEmailDocument.setDocumentName(emailTaskInstance.getName());
			bpmEmailDocument.setCreateDate(emailTaskInstance.getCreate());
			bpmEmailDocs.add(bpmEmailDocument);
		}
		
		return bpmEmailDocs;
	}
	
	Collection<TaskInstance> filterEmailsTaskInstances(
	        Collection<TaskInstance> emailsTaskInstances) {
		
		for (Iterator<TaskInstance> iterator = emailsTaskInstances.iterator(); iterator
		        .hasNext();) {
			TaskInstance taskInstance = iterator.next();
			
			if (!taskInstance.hasEnded()) {
				iterator.remove();
			} else {
				
				try {
					Permission permission = getPermissionsFactory()
					        .getTaskInstanceViewPermission(true, taskInstance);
					getBpmFactory().getRolesManager().checkPermission(
					    permission);
					
				} catch (BPMAccessControlException e) {
					iterator.remove();
				}
			}
		}
		
		return emailsTaskInstances;
	}
	
	/**
	 * @return all attachments that where added with addAttachment subprocess, and that are not
	 * hidden (binVar.getHidden() == false)
	 */
	@Transactional(readOnly = true)
	public List<BinaryVariable> getAttachements() {
		
		ArrayList<String> included = new ArrayList<String>(1);
		included.add(add_attachement_process_name);
		Collection<TaskInstance> taskInstances = getProcessTaskInstances(null,
		    included);
		
		List<BinaryVariable> attachments = new ArrayList<BinaryVariable>();
		
		for (Iterator<TaskInstance> iterator = taskInstances.iterator(); iterator
		        .hasNext();) {
			TaskInstance taskInstance = iterator.next();
			
			if (taskInstance.hasEnded()) {
				
				try {
					Permission permission = getPermissionsFactory()
					        .getTaskInstanceViewPermission(true, taskInstance);
					getBpmFactory().getRolesManager().checkPermission(
					    permission);
					
				} catch (BPMAccessControlException e) {
					continue;
				}
				
				List<BinaryVariable> allAttachments = getBpmFactory()
				                .getProcessManagerByTaskInstanceId(
				                    taskInstance.getId()).getTaskInstance(
				                    taskInstance).getAttachments();
				
				for(BinaryVariable attachment:allAttachments){
					if(attachment.getHidden() == null || !attachment.getHidden()){
						attachments.add(attachment);
					}
				}
				
			}
		}
		
		return attachments;
	}
	
	@Transactional(readOnly = false)
	public TaskInstanceW createTask(final String taskName, final long tokenId) {
		
		return getBpmContext().execute(new JbpmCallback() {
			
			public Object doInJbpm(JbpmContext context) throws JbpmException {
				ProcessInstance processInstance = getProcessInstance();
				
				@SuppressWarnings("unchecked")
				List<Token> tkns = processInstance.findAllTokens();
				
				for (Token token : tkns) {
					
					if (token.getId() == tokenId) {
						TaskInstance ti = processInstance.getTaskMgmtInstance()
						        .createTaskInstance(
						            ((TaskNode) token.getNode())
						                    .getTask(taskName), token);
						/*
						 * getBpmFactory().takeView(ti.getId(), true,
						 * preferred);
						 */

						TaskInstanceW taskInstanceW = getBpmFactory()
						        .getProcessManagerByTaskInstanceId(ti.getId())
						        .getTaskInstance(ti.getId());
						
						taskInstanceW.loadView();
						return taskInstanceW;
						
					}
				}
				
				return null;
			}
		});
		
	}
	
	@Transactional(readOnly = true)
	public TaskInstanceW getStartTaskInstance() {
		
		long id = getProcessDefinitionW().getProcessDefinition()
		        .getTaskMgmtDefinition().getStartTask().getId();
		for (TaskInstanceW taskInstanceW : getAllTaskInstances()) {
			if (taskInstanceW.getTaskInstance().getTask().getId() == id) {
				
				return taskInstanceW;
			}
		}
		return null;
	}
	
	@Transactional(readOnly = true)
	public boolean hasEnded() {
		return getProcessInstance().hasEnded();
	}
	
	public VariablesHandler getVariablesHandler() {
		return variablesHandler;
	}
	
	private UserBusiness getUserBusiness() {
		try {
			IWApplicationContext iwac = IWMainApplication
			        .getDefaultIWApplicationContext();
			return (UserBusiness) IBOLookup.getServiceInstance(iwac,
			    UserBusiness.class);
		} catch (IBOLookupException ile) {
			throw new IBORuntimeException(ile);
		}
	}
	
	public PermissionsFactory getPermissionsFactory() {
		return permissionsFactory;
	}
	
	public Collection<Role> getRolesContactsPermissions(Integer userId) {
		
		Collection<Role> roles = getBpmFactory().getRolesManager()
		        .getUserPermissionsForRolesContacts(getProcessInstanceId(),
		            userId);
		
		return roles;
	}
	
	public Object getVariableLocally(final String variableName, Token token) {
		
		if (token == null)
			token = getProcessInstance().getRootToken();
		
		ContextInstance contextInstnace = token.getProcessInstance()
		        .getContextInstance();
		
		Object val = contextInstnace.getVariableLocally(variableName, token);
		return val;
	}
}