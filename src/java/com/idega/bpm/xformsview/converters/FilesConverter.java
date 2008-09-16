package com.idega.bpm.xformsview.converters;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.idega.core.file.data.ExtendedFile;
import com.idega.core.file.tmp.TmpFileResolver;
import com.idega.core.file.tmp.TmpFileResolverType;
import com.idega.core.file.tmp.TmpFilesManager;
import com.idega.jbpm.variables.VariableDataType;
import com.idega.util.xml.XPathUtil;

/**
 * @author <a href="mailto:civilis@idega.com">Vytautas Čivilis</a>
 * @version $Revision: 1.1 $
 *
 * Last modified: $Date: 2008/09/16 17:48:15 $ by $Author: civilis $
 */
@Scope("singleton")
@Service
public class FilesConverter implements DataConverter {
	
	private TmpFilesManager uploadsManager;
	private TmpFileResolver uploadResourceResolver;
	private static final String mappingAtt = "mapping";
	private final XPathUtil entriesXPUT;
	
	public FilesConverter() {
		entriesXPUT = new XPathUtil("./entry");
	}

	public Object convert(Element ctx) {
		
		String variableName = ctx.getAttribute(mappingAtt);
		
		Collection<URI> filesUris = getUploadsManager().getFilesUris(variableName, ctx, getUploadResourceResolver());
		Collection<ExtendedFile> filesAndDescriptions = new ArrayList<ExtendedFile>();
		
		for(URI fileUri : filesUris) {
			
			String description = getDescriptionByUri(variableName, ctx, fileUri);
			ExtendedFile exFile = new ExtendedFile(fileUri, description);
			filesAndDescriptions.add(exFile);
		}
		
		return filesAndDescriptions;
	}
	
	private String getDescriptionByUri(String identifier, Object resource, URI uri) {
		
		if(!(resource instanceof Node)) {	
			Logger.getLogger(getClass().getName()).log(Level.WARNING, "Wrong resource provided. Expected of type "+Node.class.getName()+", but got "+resource.getClass().getName());
			return null;
		}
		
		String desc = null;
		
		Node instance = (Node)resource;
		Element node = getUploadsElement(identifier, instance);
		NodeList entries;
		
		entries = entriesXPUT.getNodeset(node);
		
		if(entries != null) {
			
			String uriStrMatch = uri.toString();
			
			for (int i = 0; i < entries.getLength(); i++) {
				
				String uriStr = entries.item(i).getChildNodes().item(0).getTextContent();
		    	
				if(uriStrMatch.equals(uriStr)) {
					
					Node descNode = entries.item(i).getChildNodes().item(1);
					
					if(descNode != null) {
						desc = descNode.getTextContent();
						break;
					}
				}
			}
		}
		
		return desc;
	}
	
	protected Element getUploadsElement(String identifier, Node context) {
		if(context instanceof Element && identifier.equals(((Element)context).getAttribute("mapping"))) {	
			return (Element)context;
		} else {
			return null;
		}
	}
	
	public Element revert(Object o, Element e) {
	
		Logger.getLogger(getClass().getName()).log(Level.WARNING, "UNSUPPORTED OPERATION");
		return e;
	}
	
	public VariableDataType getDataType() {
		return VariableDataType.FILES;
	}
	
	public TmpFilesManager getUploadsManager() {
		return uploadsManager;
	}
	
	@Autowired
	public void setUploadsManager(TmpFilesManager uploadsManager) {
		this.uploadsManager = uploadsManager;
	}
	public TmpFileResolver getUploadResourceResolver() {
		return uploadResourceResolver;
	}
	
	@Autowired
	public void setUploadResourceResolver(@TmpFileResolverType("xformVariables")
			TmpFileResolver uploadResourceResolver) {
		this.uploadResourceResolver = uploadResourceResolver;
	}
}