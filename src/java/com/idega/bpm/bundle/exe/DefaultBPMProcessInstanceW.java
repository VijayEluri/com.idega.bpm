package com.idega.bpm.bundle.exe;


import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Resource;

import org.jbpm.JbpmContext;
import org.jbpm.graph.exe.ProcessInstance;
import org.jbpm.graph.exe.Token;
import org.jbpm.taskmgmt.exe.TaskInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Required;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import com.idega.jbpm.BPMContext;
import com.idega.jbpm.exe.BPMFactory;
import com.idega.jbpm.exe.ProcessDefinitionW;
import com.idega.jbpm.exe.ProcessInstanceW;
import com.idega.jbpm.exe.ProcessManager;
import com.idega.jbpm.exe.ProcessWatch;
import com.idega.jbpm.exe.TaskInstanceW;
import com.idega.jbpm.identity.BPMUser;
import com.idega.jbpm.identity.Role;
import com.idega.jbpm.identity.permission.BPMTypedPermission;
import com.idega.jbpm.identity.permission.PermissionsFactory;
import com.idega.user.data.User;
import com.idega.user.util.UserComparator;
import com.idega.util.CoreUtil;

/**
 * @author <a href="mailto:civilis@idega.com">Vytautas Čivilis</a>
 * @version $Revision: 1.1 $
 *
 * Last modified: $Date: 2008/09/17 13:09:02 $ by $Author: civilis $
 */
@Scope("prototype")
@Service("defaultPIW")
public class DefaultBPMProcessInstanceW implements ProcessInstanceW {
	
	private Long processInstanceId;
	private ProcessInstance processInstance;
	
	private BPMContext idegaJbpmContext;
	private ProcessManager processManager;
	@Autowired
	private BPMFactory bpmFactory;
	@Autowired
	private PermissionsFactory permissionsFactory;
	
	public List<TaskInstanceW> getAllTaskInstances() {
		
		ProcessInstance processInstance = getProcessInstance();
		
		@SuppressWarnings("unchecked")
		Collection<TaskInstance> taskInstances = processInstance.getTaskMgmtInstance().getTaskInstances();
		return encapsulateInstances(taskInstances);
	}
	
	public List<TaskInstanceW> getUnfinishedTaskInstances(Token rootToken) {
		
		ProcessInstance processInstance = rootToken.getProcessInstance();
		
		@SuppressWarnings("unchecked")
		Collection<TaskInstance> taskInstances = processInstance.getTaskMgmtInstance().getUnfinishedTasks(rootToken);

		return encapsulateInstances(taskInstances);
	}
	
	public List<TaskInstanceW> getAllUnfinishedTaskInstances() {
	
		ProcessInstance processInstance = getProcessInstance();
	
		@SuppressWarnings("unchecked")
		Collection<TaskInstance> taskInstances = processInstance.getTaskMgmtInstance().getUnfinishedTasks(processInstance.getRootToken());
		
		@SuppressWarnings("unchecked")
		List<Token> tokens = processInstance.findAllTokens();
		
		for (Token token : tokens) {
			
			if(!token.equals(processInstance.getRootToken())) {
		
				@SuppressWarnings("unchecked")
				Collection<TaskInstance> tis = processInstance.getTaskMgmtInstance().getUnfinishedTasks(token);
				taskInstances.addAll(tis);
			}
		}

		return encapsulateInstances(taskInstances);
	}
	
	private ArrayList<TaskInstanceW> encapsulateInstances(Collection<TaskInstance> taskInstances) {
		ArrayList<TaskInstanceW> instances = new ArrayList<TaskInstanceW>(taskInstances.size());
		
		for(TaskInstance instance : taskInstances) {
			TaskInstanceW tiw = getProcessManager().getTaskInstance(instance.getId());
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

	public BPMContext getIdegaJbpmContext() {
		return idegaJbpmContext;
	}

	@Required
	@Autowired
	public void setIdegaJbpmContext(BPMContext idegaJbpmContext) {
		this.idegaJbpmContext = idegaJbpmContext;
	}

	public ProcessManager getProcessManager() {
		return processManager;
	}

	@Required
	@Resource(name="defaultBpmProcessManager")
	public void setProcessManager(ProcessManager processManager) {
		this.processManager = processManager;
	}

	public ProcessInstance getProcessInstance() {
		
		if(processInstance == null && getProcessInstanceId() != null) {
			
			JbpmContext ctx = getIdegaJbpmContext().createJbpmContext();
			
			try {
				processInstance = ctx.getProcessInstance(getProcessInstanceId());
				
			} finally {
				getIdegaJbpmContext().closeAndCommit(ctx);
			}
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
	
	public ProcessDefinitionW getProcessDefinitionW () {
		
		Long pdId = getProcessInstance().getProcessDefinition().getId();
		return getProcessManager().getProcessDefinition(pdId);
	}
	
	public Integer getHandlerId() {
		
		return null;
	}
	
	public List<User> getUsersConnectedToProcess() {
		
		final Collection<User> users;
		
		try {
			Long processInstanceId = getProcessInstanceId();
			BPMTypedPermission perm = (BPMTypedPermission)getPermissionsFactory().getRoleAccessPermission(processInstanceId, null, false);
			users = getBpmFactory().getRolesManager().getAllUsersForRoles(null, processInstanceId, perm);
			
		} catch(Exception e) {
			Logger.getLogger(getClass().getName()).log(Level.SEVERE, "Exception while resolving all process instance users", e);
			return null;
		}
		
		if(users != null && !users.isEmpty()) {

//			using separate list, as the resolved one could be cashed (shared) and so
			ArrayList<User> connectedPeople = new ArrayList<User>(users);

			for (Iterator<User> iterator = connectedPeople.iterator(); iterator.hasNext();) {
				
				User user = iterator.next();
				String hideInContacts = user.getMetaData(BPMUser.HIDE_IN_CONTACTS);
				
				if(hideInContacts != null)
//					excluding ones, that should be hidden in contacts list
					iterator.remove();
			}
			
			try {
				Collections.sort(connectedPeople, new UserComparator(CoreUtil.getIWContext().getCurrentLocale()));
			} catch(Exception e) {
				Logger.getLogger(getClass().getName()).log(Level.SEVERE, "Exception while sorting contacts list ("+connectedPeople+")", e);
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
	
		getBpmFactory().getRolesManager().setContactsPermission(
				role, processInstanceId, userId
		);
	}

	public BPMFactory getBpmFactory() {
		return bpmFactory;
	}

	public void setBpmFactory(BPMFactory bpmFactory) {
		this.bpmFactory = bpmFactory;
	}

	public PermissionsFactory getPermissionsFactory() {
		return permissionsFactory;
	}

	public void setPermissionsFactory(PermissionsFactory permissionsFactory) {
		this.permissionsFactory = permissionsFactory;
	}

	public ProcessWatch getProcessWatcher() {
		return null;
	}
}